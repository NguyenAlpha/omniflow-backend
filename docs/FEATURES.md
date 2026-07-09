# OmniFlow — Danh sách chức năng hệ thống

> Liệt kê toàn bộ chức năng theo domain. Không phân biệt đã triển khai hay chưa.

---

## 1. Xác thực (Authentication)

- Đăng ký tài khoản bằng username + email + password
- Đăng nhập bằng username hoặc email
- Mã hóa mật khẩu bằng BCrypt
- Cấp JWT access token sau đăng nhập (TTL 24h, stateless)
- Cấp refresh token sau đăng nhập (TTL 30 ngày, lưu DB table `refresh_tokens`)
- Làm mới access token qua `POST /api/auth/refresh` (token rotation: refresh token cũ bị revoke)
- Phát hiện reuse refresh token → vô hiệu hóa toàn bộ token của user (family invalidation)
- Đăng xuất `POST /api/auth/logout` → revoke tất cả refresh token của user
- Trả về danh sách store membership và role tương ứng trong response đăng nhập
- Vô hiệu hóa tài khoản (`is_active = false`) — không đăng nhập được
- Soft delete tài khoản (`deleted_at`) — không xoá vật lý

---

## 2. Phân quyền (RBAC)

- 5 role cố định: `SUPER_ADMIN`, `SUPPORT` (global), `OWNER`, `MANAGER`, `STAFF` (store-scoped)
- Global role (`store_id IS NULL`): áp dụng toàn hệ thống
- Store-scoped role (`store_id IS NOT NULL`): chỉ có hiệu lực trong store đó
- 1 user có thể có nhiều role ở nhiều store khác nhau
- Ghi nhận ai gán quyền (`granted_by`) và thời điểm gán
- Kích hoạt / vô hiệu hóa từng phân quyền (`is_active`)
- Soft delete phân quyền khi thu hồi — không xoá vật lý
- Kiểm tra quyền theo store context tại mỗi API endpoint

---

## 3. Quản lý Cửa hàng (Store)

- Tạo cửa hàng mới — tự động gán người tạo làm `OWNER`
- Xem thông tin cửa hàng
- Cập nhật thông tin cửa hàng (tên, địa chỉ, số điện thoại, email)
- Vô hiệu hóa cửa hàng (`is_active = false`)
- Xem danh sách tất cả cửa hàng mà user đang là thành viên

---

## 4. Quản lý Thành viên (Store Members)

- Thêm thành viên vào cửa hàng (kèm role và chức danh)
- Xem danh sách thành viên của cửa hàng
- Cập nhật thông tin thành viên: chức danh (`position_title`), trạng thái, role
- Không thể sửa role của OWNER khác (chỉ OWNER hiện tại mới tự sửa role mình)
- Xóa thành viên khỏi cửa hàng (soft delete `store_members` + `user_roles`)
- Không thể xóa OWNER khỏi cửa hàng

---

## 5. Quản lý Gói dịch vụ (Subscription)

- 3 gói: `FREE`, `BASIC`, `PRO` — giới hạn số store, staff, product, warehouse theo gói
- Tạo business tự động kích hoạt gói FREE (không giới hạn thời gian)
- Kiểm tra giới hạn gói trước khi tạo resource mới (store, staff, product, warehouse)

**Luồng nâng cấp gói (chuyển khoản ngân hàng thủ công):**
- Business owner tạo yêu cầu nâng cấp → hệ thống tạo invoice PENDING + cung cấp thông tin TK ngân hàng
- Owner chuyển khoản thực tế → gửi nội dung/mã CK lên hệ thống
- Admin vào web xem danh sách invoice PENDING → đối chiếu giao dịch → confirm hoặc reject
- Confirm → invoice `PAID`, subscription cập nhật plan mới + tính `expiresAt`
- Reject → invoice `FAILED`, subscription không thay đổi, owner có thể tạo yêu cầu mới

**Invoice lifecycle:**
- 1 business chỉ có tối đa 1 invoice `PENDING` tại 1 thời điểm
- Lịch sử toàn bộ invoice được lưu (`subscription_invoices`) — immutable
- Chu kỳ: `MONTHLY` (30 ngày) hoặc `YEARLY` (365 ngày)

**Hết hạn gói (scheduler hàng ngày 01:00 AM):**
- Sub ACTIVE quá `expiresAt` → `status = EXPIRED`, 4 limit hạ về gói FREE
  (soft cap — data hiện có giữ nguyên, chỉ chặn tạo mới vượt giới hạn FREE)
- `plan` giữ nguyên làm record lịch sử — UI biết gói cũ để gợi ý mua lại
- Sub EXPIRED được mua lại **bất kỳ gói trả phí nào** qua luồng nâng cấp
  (kể cả gói bằng/thấp hơn plan cũ — renewal/re-subscribe)

**Email notification (async, qua Spring Mail):**
- Admin confirm invoice → gửi email xác nhận đến business email
- Admin reject invoice → gửi email thông báo từ chối kèm lý do
- Scheduler hàng ngày (01:00 AM) → gửi email cảnh báo subscription sắp hết hạn trong 7 ngày

**Chưa triển khai:**
- Huỷ gói (`CANCELLED`)

---

## 6. Danh mục sản phẩm (Category)

- Tạo danh mục per-store
- Xem danh sách danh mục của cửa hàng
- Cập nhật tên và mô tả danh mục
- Soft delete danh mục
- Tên danh mục unique trong cùng cửa hàng

---

## 7. Đơn vị tính (Unit)

- System units (do SUPER_ADMIN quản lý, dùng chung toàn hệ thống): Cái, Kg, Lít, Hộp, Thùng, Gói, ...
- Store units (do OWNER/MANAGER cửa hàng quản lý): đơn vị tính tùy chỉnh theo nghiệp vụ
- Tạo / cập nhật / soft delete store unit
- Tên unit unique trong cùng cửa hàng (system units unique toàn hệ thống)
- Query luôn trả về cả system units lẫn store units

---

## 8. Quản lý Sản phẩm (Product)

- Tạo sản phẩm với SKU, tên, mô tả, danh mục, đơn vị tính, giá vốn, giá bán, tồn tối thiểu
- SKU unique trong cùng cửa hàng
- Cập nhật thông tin sản phẩm
- Khi cập nhật giá (giá vốn hoặc giá bán): tự động ghi vào `price_history` — immutable
- Kích hoạt / ngừng kinh doanh sản phẩm (`is_active`)
- Soft delete sản phẩm
- Lọc sản phẩm theo trạng thái (`is_active`)
- Tìm kiếm sản phẩm theo tên / SKU / mô tả (LIKE và full-text search với `tsvector` + GIN index)
- Phân trang kết quả tìm kiếm

---

## 9. Quản lý Kho (Warehouse)

- Tạo kho hàng per-store (multi-warehouse)
- Xem danh sách kho của cửa hàng
- Cập nhật thông tin kho (tên, địa chỉ)
- Kích hoạt / vô hiệu hóa kho
- Soft delete kho

---

## 10. Quản lý Tồn kho (Inventory)

- Theo dõi tồn kho theo cặp `(sản phẩm, kho)`
- Xem tồn kho hiện tại của sản phẩm trong từng kho
- Điều chỉnh tồn kho thủ công (`ADJUSTMENT`) kèm ghi chú lý do
- Cảnh báo tồn kho dưới mức tối thiểu (`min_stock_level`)
- Mọi biến động tồn kho ghi vào `inventory_transactions` — immutable:
  - `IN`: nhập hàng (từ purchase order hoặc điều chỉnh)
  - `OUT`: xuất hàng (từ order bán)
  - `TRANSFER`: chuyển kho
  - `ADJUSTMENT`: kiểm kê điều chỉnh thủ công
- Xem lịch sử giao dịch kho theo sản phẩm / kho / thời gian
- Dashboard tồn kho tổng hợp (`mv_inventory_summary`)

---

## 11. Quản lý Khách hàng (Customer)

- Tạo khách hàng với mã, tên, số điện thoại, email, địa chỉ
- Mã khách hàng unique trong cùng cửa hàng
- Cập nhật thông tin khách hàng
- Soft delete khách hàng
- Tìm kiếm khách hàng theo tên / số điện thoại / mã (full-text search)
- Xem số dư công nợ (`debt_balance`) của từng khách hàng
- Danh sách khách hàng đang có công nợ (filter `debt_balance > 0`)

---

## 12. Quản lý Nhà cung cấp (Supplier)

- Tạo nhà cung cấp với mã, tên, số điện thoại, email, địa chỉ
- Mã nhà cung cấp unique trong cùng cửa hàng
- Cập nhật thông tin nhà cung cấp
- Soft delete nhà cung cấp
- Xem số dư công nợ (`debt_balance`) — tiền cửa hàng đang nợ nhà cung cấp
- Danh sách nhà cung cấp đang có công nợ (filter `debt_balance > 0`)

---

## 13. Bán hàng — Đơn hàng (Order)

- Tạo đơn hàng: chọn kho xuất, khách hàng (hoặc khách lẻ), danh sách sản phẩm + số lượng
- Hỗ trợ chiết khấu đơn hàng (`discount_type: FIXED / PERCENT`)
- Hỗ trợ chiết khấu từng dòng sản phẩm (`order_items.discount`)
- Áp dụng thuế VAT (`tax`)
- Tính tự động: `subtotal`, `total_amount`, `debt_amount` (= total - paid)
- Ghi nhận số tiền thu ngay (`paid_amount`) khi tạo đơn
- Snapshot đơn giá tại thời điểm bán — không bị ảnh hưởng khi giá sản phẩm thay đổi sau
- Mã đơn hàng tự sinh unique trong cửa hàng
- Xem chi tiết đơn hàng
- Danh sách đơn hàng với filter theo trạng thái, thời gian
- Hoàn thành đơn hàng (`COMPLETED`):
  - Trừ tồn kho (tạo `inventory_transaction` loại `OUT`)
  - Cộng `customers.debt_balance` nếu `debt_amount > 0`
- Ghi nhận thanh toán nợ (`PUT /pay`):
  - Cập nhật `paid_amount` và `debt_amount` trên đơn hàng
  - Giảm `customers.debt_balance` nếu đơn đã `COMPLETED` và có khách hàng
- Huỷ đơn hàng (`CANCELLED`):
  - Hoàn tồn kho nếu đã `COMPLETED`
  - Rollback `customers.debt_balance` nếu đã `COMPLETED`
- Đơn hàng không xoá vật lý — chỉ huỷ qua status

---

## 14. Hoàn trả hàng (Return Order)

- Tạo đơn hoàn trả liên kết với đơn hàng gốc
- Chọn sản phẩm và số lượng hoàn trả (subset của đơn gốc)
- Đơn giá hoàn lấy snapshot từ đơn gốc
- Hình thức hoàn tiền: `CASH` / `BANK_TRANSFER` / `STORE_CREDIT`
- Ghi lý do hoàn trả bắt buộc
- Mã hoàn trả tự sinh unique trong cửa hàng
- Xem chi tiết đơn hoàn trả
- Hoàn thành đơn hoàn trả (`COMPLETED`):
  - Nhập lại tồn kho (tạo `inventory_transaction` loại `IN`)
  - Điều chỉnh `customers.debt_balance` nếu hoàn vào công nợ
- Huỷ đơn hoàn trả (`CANCELLED`)
- Đơn hoàn trả không xoá vật lý

---

## 15. Nhập hàng — Đơn nhập (Purchase Order)

- Tạo đơn nhập từ nhà cung cấp: chọn kho nhập, nhà cung cấp, danh sách sản phẩm + số lượng + giá nhập
- Tính tự động: `total_amount`, `debt_amount` (= total - paid)
- Ghi nhận số tiền đã trả ngay (`paid_amount`)
- Mã đơn nhập tự sinh unique trong cửa hàng
- Xem chi tiết đơn nhập
- Danh sách đơn nhập với filter theo trạng thái, nhà cung cấp
- Nhận hàng (`RECEIVED`):
  - Nhập tồn kho (tạo `inventory_transaction` loại `IN`)
  - Cộng `suppliers.debt_balance` nếu `debt_amount > 0`
- Huỷ đơn nhập (`CANCELLED`):
  - Rollback tồn kho nếu đã `RECEIVED`
  - Rollback `suppliers.debt_balance` nếu đã `RECEIVED`
- Đơn nhập không xoá vật lý

---

## 16. Thu chi Công nợ (Payment)

- Ghi nhận thanh toán công nợ từ khách hàng: giảm `customers.debt_balance`
- Ghi nhận trả tiền cho nhà cung cấp: giảm `suppliers.debt_balance`
- Hình thức thanh toán: `CASH` / `BANK_TRANSFER`
- Mỗi bản ghi payment chỉ thuộc về đúng 1 đối tượng: khách hàng hoặc nhà cung cấp
- Xem lịch sử thanh toán theo khách hàng / nhà cung cấp
- Payment không xoá vật lý — dữ liệu tài chính

---

## 17. Báo cáo & Dashboard

- Doanh thu theo tháng / khoảng thời gian (materialized view `mv_monthly_revenue`)
- Tổng tiền đã thu và còn nợ theo tháng
- Số đơn hàng theo trạng thái
- Tồn kho tổng hợp theo sản phẩm (materialized view `mv_inventory_summary`)
- Danh sách sản phẩm dưới mức tồn kho tối thiểu
- Công nợ khách hàng (tổng, danh sách có nợ)
- Công nợ nhà cung cấp (tổng, danh sách có nợ)
- Lịch sử thay đổi giá sản phẩm (`price_history`)

---

## 18. Nhật ký thao tác (Audit Log)

- Ghi log tự động cho các thao tác nhạy cảm:
  - Sửa giá sản phẩm
  - Huỷ đơn hàng / đơn nhập
  - Thay đổi role thành viên
  - Thay đổi gói subscription
  - Xoá khách hàng / nhà cung cấp
- Lưu trạng thái trước (`old_data` JSONB) và sau (`new_data` JSONB) mỗi thay đổi
- Ghi nhận IP address của người thực hiện
- Xem lịch sử thao tác theo bảng, record, hoặc người thực hiện
- Audit log không sửa / không xoá — bằng chứng audit

---

## 19. Audit Log

Ghi nhận mọi hành động quan trọng — ai xóa đơn hàng, ai thay đổi giá, ai điều chỉnh tồn kho.

**Bảng:** `audit_logs(id, user_id, business_id, store_id, action, entity_type, entity_id, old_value jsonb, new_value jsonb, ip, created_at)`

- Implement qua **Spring AOP** (`@Auditable` annotation + `AuditAspect`) — không đụng vào business logic
- Lưu log async (`@Async` + `Propagation.REQUIRES_NEW`) — lỗi audit không roll back business transaction
- Capture: user_id, store_id / business_id, IP, action, entity_type, new_value (JSON request)
- Các operation được audit:
  - Order: `CREATE_ORDER`, `COMPLETE_ORDER`, `PAY_ORDER`, `CANCEL_ORDER`
  - Inventory: `ADJUST_INVENTORY`
  - Product: `CREATE_PRODUCT`, `UPDATE_PRODUCT`, `DELETE_PRODUCT`
  - PurchaseOrder: `CREATE_PURCHASE_ORDER`, `RECEIVE_PURCHASE_ORDER`, `CANCEL_PURCHASE_ORDER`
- `old_value` chưa được capture (requires pre-method DB fetch — có thể mở rộng sau)

---

## 20. Idempotency Key (Order Creation)

Chống tạo đơn trùng khi client retry (mạng yếu, timeout).

- Client gửi header `Idempotency-Key: <uuid>` khi tạo đơn
- Server check Redis: key tồn tại → trả về response đã cache (24h TTL)
- Response được cache sau khi tạo đơn thành công (2xx)
- Chỉ áp dụng cho `POST /api/stores/{id}/orders`
- Header `X-Idempotency-Cached: true` được trả về khi response đến từ cache

---

## 21. Export Dữ Liệu

B2B khách hàng cần xuất báo cáo — không phải làm thủ công.

- **`GET /api/stores/{storeId}/export/orders?from=&to=`** → Excel (.xlsx) danh sách đơn hàng
  - Columns: Mã đơn, Khách hàng, Trạng thái, Tiền hàng, Giảm giá, Thuế, Tổng, Đã TT, Còn nợ, PTTT, Ngày
- **`GET /api/stores/{storeId}/export/inventory`** → Excel (.xlsx) tồn kho hiện tại
  - Columns: Sản phẩm, SKU, Kho, Số lượng, Đơn vị
- **`GET /api/stores/{storeId}/export/purchase-orders/{publicId}/pdf`** → PDF phiếu nhập hàng
  - Gồm: header (cửa hàng, nhà cung cấp, kho), bảng sản phẩm, tổng tiền, ghi chú
- Yêu cầu quyền MANAGER hoặc OWNER
- Apache POI 5.3 cho Excel, OpenPDF 2.0 cho PDF

---

## 23. API Documentation (Swagger / OpenAPI)

- Tích hợp SpringDoc OpenAPI 2.8.4 — tự động generate từ controller annotations
- Swagger UI tại `/swagger-ui/index.html`
- OpenAPI JSON tại `/v3/api-docs`
- JWT Bearer scheme được cấu hình sẵn — có thể Authorize trực tiếp trên Swagger UI
- Các endpoint Swagger được permit public (không cần token để xem docs)

---

## 24. Quản trị Hệ thống (System Admin)

- Tài khoản SUPER_ADMIN có thể truy cập mọi store mà không cần membership
- Seed tài khoản SUPER_ADMIN khi khởi động hệ thống (có thể bật/tắt qua config)
- Quản lý system units (dùng chung toàn hệ thống)
- Xem / quản lý tài khoản người dùng (khoá, mở khoá)
- Xem audit log toàn hệ thống

---

## 25. Sync Offline — Local-First (Mobile)

- Mọi entity có `public_id` (UUID) làm khóa sync ổn định giữa server và client
- `sync_version` tăng dần theo store — client biết cần pull từ version nào
- Server ghi `sync_change_log` mỗi khi có mutation: `(store_id, table, public_id, operation, sync_version)`
- Client pull delta: chỉ kéo các thay đổi có `sync_version > last_known_version`
- Ghi nhận thiết bị thực hiện thay đổi (`last_modified_by_device`) — phục vụ conflict detection
- Soft delete thay vì hard delete — client offline nhận được tín hiệu "record đã bị xoá"
- Conflict resolution: last-write-wins theo `sync_version` (chiến lược mặc định)
