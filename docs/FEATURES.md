# QuikTech POS — Danh sách chức năng hệ thống

> Liệt kê toàn bộ chức năng theo domain. Mục nào chưa triển khai được ghi rõ **Chưa triển khai**.
> Chi tiết request/response của từng endpoint xem trong [api/](api/).

---

## 1. Xác thực (Authentication)

- Đăng ký tài khoản bằng username + email + password (+ họ tên, số điện thoại)
- Sau đăng ký, client gọi `POST /api/businesses/default` để tạo bộ mặc định: business
  "Doanh nghiệp của tôi" + gói FREE + store "Cửa hàng số 1" + kho "Kho số 1", người dùng là
  `OWNER` (idempotent — gọi lại khi đã là OWNER thì trả về business hiện có, không tạo trùng)
- Đăng nhập bằng username hoặc email
- Mã hóa mật khẩu bằng BCrypt
- Cấp JWT access token sau đăng nhập (TTL mặc định 1 giờ — `JWT_EXPIRATION`, stateless)
- Cấp refresh token sau đăng nhập (TTL 30 ngày, lưu DB table `refresh_tokens`)
- Làm mới access token qua `POST /api/auth/refresh` (token rotation: refresh token cũ bị revoke)
- Phát hiện reuse refresh token → vô hiệu hóa toàn bộ token của user (family invalidation)
- Refresh bị chặn và thu hồi toàn bộ token nếu tài khoản đã bị khóa hoặc xóa
- Đăng xuất `POST /api/auth/logout` → revoke tất cả refresh token của user
- Response đăng nhập trả về danh sách business membership kèm các store được truy cập
  (OWNER → mọi store của business; MANAGER/STAFF → store được gán)
- Giới hạn đăng nhập sai theo tài khoản: 5 lần sai / 15 phút (username và email dùng chung
  bucket; chỉ sai mật khẩu mới bị tính) — ngoài rate limit theo IP ở mục 21
- Vô hiệu hóa tài khoản (`is_active = false`) — không đăng nhập được
- Soft delete tài khoản (`deleted_at`) — không xoá vật lý

---

## 2. Hồ sơ người dùng (User)

- Xem / cập nhật hồ sơ của chính mình (`GET/PATCH /api/users/me`) — username/email không được trùng
- Lấy memberships mới nhất (`GET /api/users/me/memberships`) — cùng format response login, để client
  đồng bộ store switcher khi quyền thay đổi sau lúc đăng nhập
- Đổi mật khẩu (`PATCH /api/users/me/password`) — phải nhập đúng mật khẩu hiện tại; đổi xong
  thu hồi toàn bộ refresh token (access token cũ vẫn sống tới khi hết hạn)
- Tra cứu user theo username (`GET /api/users/lookup`) — dùng khi thêm thành viên

---

## 3. Phân quyền (RBAC)

- 6 role cố định, chia 3 cấp:
  - Global (`business_id`, `store_id` đều NULL): `SUPER_ADMIN`, `SUPPORT`
  - Cấp business (`store_id` NULL): `OWNER`, `BUSINESS_MANAGER` (trợ lý — quản lý mọi store
    của business, không đụng billing/hồ sơ business/quản lý trợ lý)
  - Cấp store: `MANAGER`, `STAFF`
- 1 user có thể có nhiều role ở nhiều business/store khác nhau
- Ghi nhận ai gán quyền (`granted_by`) và thời điểm gán
- Kích hoạt / vô hiệu hóa từng phân quyền (`is_active`)
- Soft delete phân quyền khi thu hồi — không xoá vật lý
- Kiểm tra quyền tại mỗi endpoint qua `@PreAuthorize` với 2 evaluator:
  - `businessAccess`: `isMember`, `isOwnerOrManager` (OWNER/BUSINESS_MANAGER hoặc MANAGER của
    một store trong business), `isOwner` (chỉ OWNER thật)
  - `storeAccess`: `isMember` (OWNER/BUSINESS_MANAGER hoặc MANAGER/STAFF của store),
    `isOwnerOrManager`, `isOwner` (OWNER hoặc BUSINESS_MANAGER)
- `SUPER_ADMIN` qua được mọi kiểm tra business/store
- Role được cache trên Redis (TTL 5 phút), evict sau khi transaction thay đổi quyền commit

---

## 4. Doanh nghiệp (Business)

- Tạo business — tự động tạo gói FREE và gán người tạo làm `OWNER`
- Xem danh sách business mà user thuộc về, xem chi tiết business
- Cập nhật thông tin business (tên, địa chỉ, số điện thoại, email) — chỉ OWNER
- Bật / tắt trạng thái business (`is_active`) — chỉ OWNER
  > **Lưu ý:** `is_active` của business/store hiện chỉ mang tính **hiển thị** (cờ trạng thái
  > cho UI) — chưa có evaluator/service nào chặn truy cập khi `is_active = false`.
  > Không dùng cờ này cho nghiệp vụ đình chỉ tenant; enforce sẽ bổ sung khi có yêu cầu thật.

---

## 5. Thành viên cấp business (Business Member / Trợ lý)

- OWNER thêm trợ lý (`BUSINESS_MANAGER`) vào business — tính vào quota `max_staff` của gói
- Xem danh sách thành viên cấp business kèm role
- Cập nhật trạng thái (`is_active`) của trợ lý
- Xóa trợ lý (soft delete membership + role)
- Không thể sửa / xóa OWNER qua endpoint này

---

## 6. Quản lý Cửa hàng (Store)

- Tạo cửa hàng trong business — chỉ OWNER, tôn trọng giới hạn số store của gói
- Xem thông tin cửa hàng
- Cập nhật thông tin cửa hàng (tên, địa chỉ, số điện thoại, email) — OWNER/MANAGER
- Bật / tắt cửa hàng (`is_active`, chỉ mang tính hiển thị — xem lưu ý mục 4)
- Xem danh sách tất cả cửa hàng mà user truy cập được

---

## 7. Thành viên cửa hàng (Store Members)

- OWNER/BUSINESS_MANAGER thêm thành viên vào cửa hàng (kèm role và chức danh)
- Role cấp store chỉ được là `MANAGER` hoặc `STAFF` (OWNER nằm ở cấp business, không phải
  thành viên store)
- Tính vào quota `max_staff` của gói; một user không được thêm trùng vào cùng store
- Xem danh sách thành viên của cửa hàng
- Cập nhật thông tin thành viên: chức danh (`position_title`), trạng thái, role
- Xóa thành viên khỏi cửa hàng (soft delete `store_members` + `user_roles`)

---

## 8. Quản lý Gói dịch vụ (Subscription)

- 3 gói: `FREE`, `BASIC`, `PRO` — giới hạn số store, staff, product, warehouse theo gói
- Giá (tháng/năm) và 4 giới hạn của từng gói lưu trong DB, SUPER_ADMIN chỉnh được (mục 18);
  danh sách gói công khai tại `GET /api/plans`
- Tạo business tự động kích hoạt gói FREE (không giới hạn thời gian)
- Kiểm tra giới hạn gói trước khi tạo resource mới (store, staff, product, warehouse)
- Staff limit đếm cả member `isActive = false` (member tạm khóa vẫn chiếm quota) —
  chỉ member bị xóa (soft delete) mới trả lại quota
- Xem số sản phẩm còn được tạo theo gói (`GET .../products/limit`)

**Luồng nâng cấp gói (chuyển khoản ngân hàng thủ công):**
- Business owner tạo yêu cầu nâng cấp → hệ thống tạo invoice PENDING, chụp lại (snapshot)
  tài khoản nhận tiền đang dùng vào invoice và trả về thông tin chuyển khoản
- Sub ACTIVE chỉ được nâng lên gói cao hơn; không thể "nâng cấp" lên FREE
- Owner chuyển khoản thực tế → gửi nội dung/mã CK lên hệ thống
- Owner tự huỷ được invoice khi còn `PENDING`
- Xem ảnh QR chuyển khoản của invoice (`GET .../invoices/{invoiceId}/qr`)
- Admin xem danh sách invoice PENDING → đối chiếu giao dịch → confirm hoặc reject
- Confirm → invoice `PAID`, subscription cập nhật plan mới + tính `expiresAt`
- Reject → invoice `FAILED`, subscription không thay đổi, owner có thể tạo yêu cầu mới

**Hạ gói (downgrade):**
- Owner đặt lịch hạ xuống gói thấp hơn, có hiệu lực cuối chu kỳ hiện tại; không hoàn tiền
- Owner huỷ được lịch hạ gói đã đặt
- Đến hạn: hạ về FREE → sub vẫn ACTIVE; hạ về gói trả phí thấp hơn → sub chuyển EXPIRED,
  owner mua lại gói đó qua luồng nâng cấp

**Invoice lifecycle:**
- 1 business chỉ có tối đa 1 invoice `PENDING` tại 1 thời điểm (backstop DB: unique index V9)
- Invoice `PENDING` quá hạn thanh toán (mặc định 7 ngày) bị scheduler tự chuyển sang `FAILED`
- Kỳ sử dụng tính lại từ thời điểm admin confirm — không dùng kỳ chốt lúc tạo request
- Lịch sử toàn bộ invoice được lưu (`subscription_invoices`)
- Đổi giá gói hoặc tài khoản nhận tiền chỉ áp dụng cho invoice mới; invoice cũ giữ số tiền và
  thông tin nhận tiền đã chụp lúc tạo
- Chu kỳ: `MONTHLY` (30 ngày) hoặc `YEARLY` (365 ngày)

**Hết hạn gói (scheduler hàng ngày 01:00 AM):**
- Áp dụng các lịch hạ gói đến hạn
- Sub ACTIVE quá `expiresAt` → `status = EXPIRED`, 4 limit hạ về gói FREE
  (soft cap — data hiện có giữ nguyên, chỉ chặn tạo mới vượt giới hạn FREE)
- `plan` giữ nguyên làm record lịch sử — UI biết gói cũ để gợi ý mua lại
- Sub EXPIRED được mua lại **bất kỳ gói trả phí nào** qua luồng nâng cấp
  (kể cả gói bằng/thấp hơn plan cũ — renewal/re-subscribe)

**Email notification (async, qua Spring Mail):**
- Admin confirm invoice → gửi email xác nhận đến business email
- Admin reject invoice → gửi email thông báo từ chối kèm lý do
- Scheduler hàng ngày (01:00 AM) → gửi email cảnh báo subscription sắp hết hạn trong 7 ngày
  (mỗi chu kỳ chỉ cảnh báo 1 lần)

**Chưa triển khai:**
- Huỷ gói (`CANCELLED`)

---

## 9. Danh mục sản phẩm (Category)

- Tạo danh mục per-business (dùng chung cho mọi store trong business)
- Xem danh sách danh mục của business
- Cập nhật tên và mô tả danh mục
- Soft delete danh mục — bị chặn nếu còn sản phẩm (chưa xóa) tham chiếu danh mục đó
- Tên danh mục unique trong cùng business

---

## 10. Đơn vị tính (Unit)

- System units (do SUPER_ADMIN quản lý, dùng chung toàn hệ thống): Cái, Kg, Lít, Hộp, Thùng, Gói, ...
- Business units (do OWNER/MANAGER của business quản lý): đơn vị tính tùy chỉnh theo nghiệp vụ
- Tạo / cập nhật / soft delete business unit — xóa bị chặn nếu còn sản phẩm (chưa xóa) dùng unit đó
- Không sửa / xóa được system unit qua endpoint của business
- Tên unit unique trong cùng business (system units unique toàn hệ thống;
  business unit không được đặt trùng tên system unit)
- Query luôn trả về cả system units lẫn business units

---

## 11. Quản lý Sản phẩm (Product)

- Tạo sản phẩm với SKU, tên, mô tả, danh mục, đơn vị tính, giá vốn, giá bán, tồn tối thiểu
- SKU unique trong cùng business (mọi store của business dùng chung catalog)
- Tra cứu sản phẩm theo SKU (`GET .../products/sku/{sku}`) — phục vụ quét mã vạch
- Xem chi tiết sản phẩm kèm lịch sử giá
- Cập nhật thông tin sản phẩm
- Khi cập nhật giá (giá vốn hoặc giá bán): tự động ghi vào `price_history` — immutable
- Kích hoạt / ngừng kinh doanh sản phẩm (`is_active`)
- Soft delete sản phẩm — bị chặn nếu còn tồn kho (phải điều chỉnh về 0 trước)
- Lọc sản phẩm theo trạng thái (`is_active`) và danh mục
- Tìm kiếm sản phẩm bằng full-text search (`search_vector @@ plainto_tsquery('simple', unaccent(...))`,
  GIN index — tìm được cả khi gõ không dấu), có phân trang
- Import sản phẩm hàng loạt từ file CSV (parse chuẩn RFC 4180, tối đa 1.000 dòng/file):
  validate và báo lỗi theo từng dòng (giá âm, thiếu cột, SKU trùng, category không tồn tại),
  dùng được cả system unit lẫn unit của business, tôn trọng giới hạn sản phẩm của gói
  (các dòng vượt limit bị bỏ qua kèm thông báo, không rollback các dòng đã hợp lệ)

---

## 12. Quản lý Kho (Warehouse)

- Tạo kho hàng per-store (multi-warehouse) — tôn trọng giới hạn số kho của gói
- Tên kho unique trong cùng cửa hàng
- Xem danh sách kho / chi tiết kho của cửa hàng
- Cập nhật thông tin kho (tên, địa chỉ, trạng thái)
- Kích hoạt / vô hiệu hóa kho — kho inactive không dùng được để bán, nhập, hay làm kho đích khi chuyển
- Soft delete kho — bị chặn nếu kho còn hàng (phải chuyển hết hàng ra trước)

---

## 13. Quản lý Tồn kho (Inventory)

- Theo dõi tồn kho theo cặp `(sản phẩm, kho)`
- Xem tồn kho hiện tại của cửa hàng, lọc theo kho
- Điều chỉnh tồn kho thủ công (`ADJUSTMENT`) kèm ghi chú lý do — không cho tồn âm
- Chuyển kho giữa 2 kho khác nhau trong cùng cửa hàng — kiểm tra đủ tồn ở kho nguồn
- Điều chỉnh / chuyển kho **hàng loạt** (`/adjust/bulk`, `/transfer/bulk`): tối đa 200 dòng/lần,
  không trùng sản phẩm, xử lý trong 1 transaction
- Cảnh báo tồn kho dưới mức tối thiểu (`min_stock_level`)
- Mọi biến động tồn kho ghi vào `inventory_transactions` — immutable:
  - `IN`: nhập hàng (từ purchase order hoặc hàng trả lại)
  - `OUT`: xuất hàng (từ order bán)
  - `TRANSFER`: chuyển kho
  - `ADJUSTMENT`: kiểm kê điều chỉnh thủ công
- Xem lịch sử giao dịch kho của cửa hàng
- Dashboard tồn kho tổng hợp (`mv_inventory_summary`)

---

## 14. Quản lý Khách hàng (Customer)

- Tạo khách hàng với mã, tên, số điện thoại, email, địa chỉ
- Mã khách hàng unique trong cùng business (mọi store của business dùng chung danh sách khách hàng)
- Cập nhật thông tin khách hàng
- Soft delete khách hàng — bị chặn nếu còn công nợ chưa tất toán
- Tìm kiếm khách hàng theo tên / mã / số điện thoại / email (substring ILIKE,
  ký tự wildcard `%`/`_` trong từ khóa được escape; chưa dùng full-text search
  dù schema đã có `search_vector` + GIN index), có phân trang
- Xem số dư công nợ (`debt_balance`) của từng khách hàng
- Thu nợ khách hàng (`PUT .../{publicId}/pay`): không vượt số nợ, ghi `payment`, và phân bổ
  vào các đơn còn nợ theo thứ tự đơn cũ trước

---

## 15. Quản lý Nhà cung cấp (Supplier)

- Tạo nhà cung cấp với mã, tên, số điện thoại, email, địa chỉ
- Mã nhà cung cấp unique trong cùng business (mọi store của business dùng chung danh sách NCC)
- Cập nhật thông tin nhà cung cấp
- Soft delete nhà cung cấp — bị chặn nếu còn công nợ chưa tất toán
- Tìm kiếm nhà cung cấp (substring ILIKE), có phân trang
- Xem số dư công nợ (`debt_balance`) — tiền business đang nợ nhà cung cấp
- Trả nợ nhà cung cấp (`PUT .../{publicId}/pay`): không vượt số nợ, ghi `payment`, phân bổ
  vào các đơn nhập còn nợ theo thứ tự đơn cũ trước

---

## 16. Bán hàng — Đơn hàng (Order)

- Tạo đơn hàng: chọn kho xuất (phải đang active), khách hàng (hoặc khách lẻ), danh sách sản phẩm + số lượng
- **Trừ tồn kho ngay khi tạo đơn** (tạo `inventory_transaction` loại `OUT`) — báo lỗi nếu không đủ hàng
- Hỗ trợ chiết khấu đơn hàng (`discount_type: FIXED / PERCENT`, PERCENT tối đa 100)
- Hỗ trợ chiết khấu từng dòng sản phẩm (`order_items.discount`) — thành tiền dòng không được âm
- Áp dụng thuế VAT (`tax`)
- Tính tự động: `subtotal`, `total_amount`, `debt_amount` (= total - paid) — tổng không được âm
- Ghi nhận số tiền thu ngay (`paid_amount`) khi tạo đơn — không vượt tổng tiền;
  **khách lẻ phải thanh toán đủ**
- Hình thức thanh toán: `CASH`, `BANK_TRANSFER`, `CREDIT_CARD`, `DEBIT_CARD`, `MOBILE_PAYMENT`, `OTHER`
- Snapshot đơn giá tại thời điểm bán — không bị ảnh hưởng khi giá sản phẩm thay đổi sau
- Mã đơn hàng tự sinh unique trong cửa hàng
- Chống tạo đơn trùng bằng `Idempotency-Key` (mục 20)
- Xem chi tiết đơn hàng
- Danh sách đơn hàng có phân trang, lọc theo mã đơn, trạng thái, khoảng ngày, khách hàng
- Hoàn thành đơn hàng (`PENDING` → `COMPLETED`):
  - Cộng `customers.debt_balance` nếu `debt_amount > 0`
  - Ghi `payment` cho số tiền đã thu
- Ghi nhận thanh toán nợ (`PUT /pay`): không vượt số còn nợ, không áp dụng cho đơn đã huỷ
  - Cập nhật `paid_amount` và `debt_amount` trên đơn hàng
  - Nếu đơn đã `COMPLETED` và có khách hàng: giảm `customers.debt_balance` và ghi `payment`
- Huỷ đơn hàng (`PENDING` → `CANCELLED`):
  - Hoàn lại tồn kho đã trừ
  - **Bị chặn nếu đơn đã thu tiền** (kể cả một phần) — phải hoàn qua đơn trả hàng (mục 17)
- Đơn đã `COMPLETED` hoặc `CANCELLED` không chuyển trạng thái được nữa
- Đơn hàng không xoá vật lý — chỉ huỷ qua status

---

## 17. Hoàn trả hàng (Return Order)

- Tạo đơn hoàn trả liên kết với đơn hàng gốc — chỉ đơn `COMPLETED` mới trả được
- Chọn sản phẩm và số lượng hoàn trả: sản phẩm phải có trong đơn gốc, tổng đã trả (không tính
  đơn trả đã huỷ) không vượt số đã mua
- Kho nhập lại = kho xuất của đơn gốc (client không tự chọn)
- Đơn giá hoàn = giá hiệu dụng sau chiết khấu dòng của đơn gốc (không nhận từ client)
- Hình thức hoàn tiền: `CASH` / `BANK_TRANSFER` / `STORE_CREDIT`
- Ghi lý do hoàn trả
- Mã hoàn trả unique trong cửa hàng
- Xem danh sách / chi tiết đơn hoàn trả
- Hoàn thành đơn hoàn trả (`PENDING` → `COMPLETED`):
  - Nhập lại tồn kho (tạo `inventory_transaction` loại `IN`)
  - Trừ vào phần còn nợ của đơn gốc và `customers.debt_balance` trước
  - Phần vượt quá số nợ được ghi là `payment` âm (tiền hoàn thực chi)
  - Cộng dồn khoản hoàn vào đơn gốc để báo cáo doanh thu trừ phần đã trả
- Huỷ đơn hoàn trả (`PENDING` → `CANCELLED`)
- Đơn hoàn trả không xoá vật lý

---

## 18. Nhập hàng — Đơn nhập (Purchase Order)

- Tạo đơn nhập từ nhà cung cấp: chọn kho nhập (phải active), nhà cung cấp, danh sách sản phẩm + số lượng + giá nhập
- Tính tự động: `total_amount`, `debt_amount` (= total - paid)
- Ghi nhận số tiền đã trả ngay (`paid_amount`) — không vượt tổng tiền
- Mã đơn nhập tự sinh unique trong cửa hàng
- Xem chi tiết đơn nhập
- Danh sách đơn nhập có phân trang, lọc theo mã đơn, trạng thái, khoảng ngày
- Nhận hàng (`PENDING` → `RECEIVED`):
  - Nhập tồn kho (tạo `inventory_transaction` loại `IN`)
  - Cộng `suppliers.debt_balance` nếu `debt_amount > 0`
  - Ghi `payment` cho số tiền đã trả
- Trả nợ đơn nhập (`PUT /pay`): không vượt số còn nợ, không áp dụng cho đơn đã huỷ;
  nếu đơn đã `RECEIVED` thì giảm `suppliers.debt_balance` và ghi `payment`
- Huỷ đơn nhập (`PENDING` → `CANCELLED`) — đơn đã `RECEIVED` không huỷ được
- Đơn nhập không xoá vật lý

---

## 19. Thu chi Công nợ (Payment)

- Ghi nhận thanh toán công nợ từ khách hàng: giảm `customers.debt_balance`
- Ghi nhận trả tiền cho nhà cung cấp: giảm `suppliers.debt_balance`
- Số tiền phải > 0 và không vượt số dư công nợ
- Hình thức thanh toán: `CASH`, `BANK_TRANSFER`, `CREDIT_CARD`, `DEBIT_CARD`, `MOBILE_PAYMENT`, `OTHER`
- Mỗi bản ghi payment chỉ thuộc về đúng 1 đối tượng: khách hàng hoặc nhà cung cấp
- Sổ quỹ: danh sách payment có phân trang, lọc theo chiều thu/chi, hình thức, khoảng ngày;
  trả kèm tổng thu (`totalIncome`) và tổng chi (`totalExpense`)
- Xoá payment (OWNER/MANAGER): hoàn lại công nợ đã trừ khi tạo; **không xoá được payment hoàn tiền**
  (payment âm của đơn trả hàng — phải huỷ đơn trả hàng)

---

## 20. Idempotency Key (Order Creation)

Chống tạo đơn trùng khi client retry (mạng yếu, timeout).

- Client gửi header `Idempotency-Key: <uuid>` khi tạo đơn (tối đa 128 ký tự)
- Chỉ áp dụng cho `POST /api/stores/{id}/orders` **đã xác thực JWT** — filter chạy trong
  Spring Security chain, sau rate limit theo user
- Key gắn với user và store (`idem:<userId>:<storeId>:<key>`): hai client trùng key không
  nhận response của nhau
- Giữ chỗ bằng `SETNX` (60s): request trùng key đến khi request đầu còn đang xử lý → `409
  IDEMPOTENCY_REQUEST_IN_PROGRESS`
- Thành công (2xx) → lưu status + body 24h; retry nhận lại **đúng status** (201) và body, kèm
  header `X-Idempotency-Cached: true`
- Lỗi (4xx/5xx) → xóa key, client sửa dữ liệu rồi gửi lại cùng key được
- Redis lỗi → xử lý như không có idempotency (không chặn tạo đơn)
- Không so sánh body giữa các request cùng key — gửi lại cùng key với body khác vẫn nhận
  response của lần đầu

---

## 21. Rate Limiting

Giới hạn tần suất request bằng Bucket4j trên Redis (chi tiết: [RATE_LIMITING.md](RATE_LIMITING.md)).

- Theo IP (trước khi xác thực): đăng nhập 10/phút, đăng ký 5/phút, refresh 20/phút,
  toàn bộ API 1.200/phút
- Theo user (sau khi xác thực): toàn bộ API 300/phút; riêng import sản phẩm 5, export 10,
  điều chỉnh/chuyển kho hàng loạt 10, đổi mật khẩu 5 — mỗi 10 phút
- Vượt giới hạn → `429` kèm thông tin thời gian chờ
- Đăng nhập sai theo tài khoản giới hạn riêng (mục 1)
- IP thật của client đọc qua danh sách proxy tin cậy (`rate-limit.trusted-proxies`)

---

## 22. Báo cáo & Dashboard

- Dashboard cửa hàng (`GET /api/stores/{storeId}/dashboard`, mọi thành viên store):
  - KPI tháng này so với tháng trước: doanh thu, tiền đã thu, số đơn; tổng số khách hàng
  - Biểu đồ doanh thu + số đơn theo tháng (materialized view `mv_monthly_revenue`)
  - Danh sách sản phẩm dưới mức tồn kho tối thiểu (`mv_inventory_summary`)
  - Đơn hàng gần đây
- Materialized view được refresh `CONCURRENTLY` mỗi 15 phút
- Công nợ khách hàng / nhà cung cấp xem qua số dư `debt_balance` trên từng đối tượng
- Lịch sử thay đổi giá sản phẩm (`price_history`) xem trong chi tiết sản phẩm
- Thống kê toàn hệ thống cho SUPER_ADMIN: xem mục 24

---

## 23. Nhật ký thao tác (Audit Log)

Hệ thống có 2 cơ chế ghi audit, cùng lưu vào bảng
`audit_logs(id, user_id, business_id, store_id, action, entity_type, entity_id, old_value jsonb, new_value jsonb, ip, created_at)`.
Audit log không sửa / không xoá — bằng chứng audit.

**Audit nghiệp vụ (merchant):**
- Implement qua **Spring AOP** (`@Auditable` annotation + `AuditAspect`) — không đụng vào business logic
- Lưu log async (`@Async` + `Propagation.REQUIRES_NEW`) — lỗi audit không roll back business transaction
- Capture: user_id, store_id / business_id, IP, action, entity_type, new_value (JSON request)
- Các operation được audit:
  - Order: `CREATE_ORDER`, `COMPLETE_ORDER`, `PAY_ORDER`, `CANCEL_ORDER`
  - Inventory: `ADJUST_INVENTORY`, `TRANSFER_INVENTORY`, `BULK_ADJUST_INVENTORY`, `BULK_TRANSFER_INVENTORY`
  - Product: `CREATE_PRODUCT`, `UPDATE_PRODUCT`, `SET_PRODUCT_STATUS`, `DELETE_PRODUCT`, `IMPORT_PRODUCTS`
  - PurchaseOrder: `CREATE_PURCHASE_ORDER`, `RECEIVE_PURCHASE_ORDER`, `CANCEL_PURCHASE_ORDER`
  - Customer: `PAY_CUSTOMER_DEBT`, `DELETE_CUSTOMER`
  - Supplier: `PAY_SUPPLIER_DEBT`, `DELETE_SUPPLIER`
  - Category / Unit: `DELETE_CATEGORY`, `DELETE_UNIT`
- `old_value` chưa được capture (requires pre-method DB fetch — có thể mở rộng sau)
- **Chưa triển khai:** API đọc audit nghiệp vụ; audit thay đổi role thành viên

**Audit quản trị viên (SUPER_ADMIN):**
- Ghi đồng bộ trong cùng transaction với thao tác (`Propagation.MANDATORY`) — ghi audit lỗi thì
  rollback luôn thao tác
- Lưu người thực hiện (ID + tên tại thời điểm đó), lý do (`reason`), snapshot trước/sau
- 10 action: `ADMIN_PLAN_CHANGED`, `ADMIN_PLAN_CONFIG_UPDATED`, `ADMIN_INVOICE_CONFIRMED`,
  `ADMIN_INVOICE_REJECTED`, `ADMIN_USER_STATUS_CHANGED`, `ADMIN_USER_DELETED`,
  `ADMIN_PAYMENT_ACCOUNT_CREATED`, `ADMIN_PAYMENT_ACCOUNT_UPDATED`,
  `ADMIN_PAYMENT_ACCOUNT_ACTIVATED`, `ADMIN_PAYMENT_ACCOUNT_ARCHIVED`
- Đọc qua `GET /api/admin/audit-logs` (mục 24)

---

## 24. Quản trị Hệ thống (System Admin)

Mọi endpoint `/api/admin/**` yêu cầu `SUPER_ADMIN`. Chi tiết:
[api/ADMIN_OPERATIONS.md](api/ADMIN_OPERATIONS.md), [api/ADMIN_USER.md](api/ADMIN_USER.md),
[api/PAYMENT_ACCOUNTS.md](api/PAYMENT_ACCOUNTS.md).

- Tài khoản SUPER_ADMIN truy cập được mọi business/store mà không cần membership
- Seed tài khoản SUPER_ADMIN và dữ liệu demo khi khởi động (bật/tắt qua `SEED_ENABLED`)
- Kiểm tra phiên admin (`GET /api/admin/session`) — web gọi trước khi vào trang quản trị
- Thống kê tổng quan (`GET /api/admin/subscriptions/stats`): số business, user (tổng/đang hoạt
  động), invoice chờ duyệt, số business theo gói, subscription active/expired, doanh thu tháng
  này và 6 tháng gần nhất
- Xem chi tiết business: thông tin, subscription và danh sách store
- Quản lý subscription:
  - Xem subscription của business, đổi gói trực tiếp (override plan) kèm lý do
  - Danh sách invoice PENDING, đếm số invoice PENDING
  - Tìm kiếm lịch sử invoice toàn hệ thống: lọc theo status, business, từ khóa (tên business,
    mã chuyển khoản, ID); tối đa 100 dòng/trang
  - Confirm / reject invoice (lý do ghi vào `adminNote`); confirm đồng thời chỉ áp dụng đúng 1 lần
- Quản lý bảng giá gói (`/api/admin/plans`): sửa giá tháng/năm và 4 giới hạn (null = không giới
  hạn); gói FREE phải có giá 0; chống ghi đè bằng `version`
- Quản lý tài khoản nhận tiền (`/api/admin/payment-accounts`): tạo, sửa, chọn tài khoản đang dùng,
  lưu trữ; upload ảnh QR (PNG/JPEG); không lưu trữ được tài khoản đang dùng; không tạo trùng
  tài khoản ngân hàng
- Quản lý người dùng (`/api/admin/users`): tìm kiếm có phân trang, sửa hồ sơ, khóa / mở khóa,
  xóa mềm (kèm lý do tùy chọn) — khóa/xóa đều thu hồi toàn bộ refresh token; xóa còn gỡ mọi
  role của user
- Quản lý system units (dùng chung toàn hệ thống)
- Xem audit quản trị viên: phân trang cursor, lọc theo business, người thực hiện, action
- Dashboard lưu lượng API (`GET /api/admin/traffic?range=1h|24h|7d|30d`):
  - `ApiTrafficFilter` đo mọi request `/api/**` (kể cả 401/429), ghi theo route pattern và business
  - Tổng hợp mỗi phút vào DB (V11): bảng theo phút giữ 2 ngày, bảng theo giờ giữ 30 ngày
  - Trả về tổng quan (số request, 4xx/5xx/429, avg/p50/p95/p99/max), chuỗi thời gian, phân bố
    HTTP status, top 50 endpoint, top 20 business; dữ liệu trễ khoảng 1–2 phút
- Tình trạng hệ thống (`GET /api/admin/traffic/system`): health từng thành phần (db, redis…),
  uptime, heap, CPU, thread, Hikari pool — giá trị tức thời của instance trả lời

---

## 25. Export Dữ Liệu

B2B khách hàng cần xuất báo cáo — không phải làm thủ công.

- **`GET /api/stores/{storeId}/export/orders?from=&to=`** → Excel (.xlsx) danh sách đơn hàng
  - Columns: Mã đơn, Khách hàng, Trạng thái, Tiền hàng, Giảm giá, Thuế, Tổng, Đã TT, Còn nợ, PTTT, Ngày
- **`GET /api/stores/{storeId}/export/inventory`** → Excel (.xlsx) tồn kho hiện tại
  - Columns: Sản phẩm, SKU, Kho, Số lượng, Đơn vị
- **`GET /api/stores/{storeId}/export/purchase-orders/{publicId}/pdf`** → PDF phiếu nhập hàng
  - Gồm: header (cửa hàng, nhà cung cấp, kho), bảng sản phẩm, tổng tiền, ghi chú
- Yêu cầu quyền MANAGER hoặc OWNER; giới hạn 10 lần export / 10 phút mỗi user
- Apache POI 5.4 cho Excel, OpenPDF 2.0 cho PDF

---

## 26. Thông báo trong ứng dụng

- Lưu sự kiện tồn kho thấp, thanh toán gói được duyệt/từ chối hoặc hết hạn chờ,
  gói sắp hết hạn trong 7 ngày và gói đã hết hạn.
- Lưu trạng thái đọc theo user; đọc tất cả theo mốc ID, phân trang cursor.
- Cảnh báo tồn kho theo cửa hàng; thông báo gói chỉ cho OWNER của business.
- Bộ thu thập chạy mỗi 60 giây, chống trùng bằng event key và khóa giao dịch;
  số cảnh báo nghiệp vụ hiện tại tách riêng khỏi số thông báo chưa đọc.
- API, giới hạn thu thập định kỳ và migration baseline V8 được mô tả tại
  [api/NOTIFICATIONS.md](api/NOTIFICATIONS.md).

---

## 27. API Documentation (Swagger / OpenAPI)

- Tích hợp SpringDoc OpenAPI 3.1 — tự động generate từ controller annotations
- Swagger UI tại `/swagger-ui/index.html`
- OpenAPI JSON tại `/v3/api-docs`
- JWT Bearer scheme được cấu hình sẵn — có thể Authorize trực tiếp trên Swagger UI
- Các endpoint Swagger được permit public (không cần token để xem docs)

---

## 28. Vận hành & Giám sát

- Actuator chạy trên port quản trị riêng `9090` (tách khỏi port API): `health` (kèm probe
  liveness/readiness) và `prometheus`
- Metric Prometheus qua Micrometer, gồm metric rate limit
- Request bị client ngắt giữa chừng (đóng tab, huỷ tải ảnh QR) không bị log thành lỗi server

---

## 29. Sync Offline — Local-First (Mobile)

**Chưa triển khai** — hiện mới có nền tảng dữ liệu:

- Mọi entity có `public_id` (UUID) làm khóa sync ổn định giữa server và client
- Các trường sync (`last_modified_at`, `last_modified_by_user`, ...) được cập nhật khi có mutation
- Soft delete thay vì hard delete — client offline sẽ nhận được tín hiệu "record đã bị xoá"
- Đã có bảng `sync_change_log` `(store_id, table, public_id, operation, sync_version)` nhưng
  server **chưa ghi** vào bảng này
- Chưa có API pull delta (`sync_version > last_known_version`), ghi nhận thiết bị
  (`last_modified_by_device`) và conflict resolution (dự kiến last-write-wins theo `sync_version`)
