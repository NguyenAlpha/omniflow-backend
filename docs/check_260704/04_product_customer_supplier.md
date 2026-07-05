# Đánh giá Product / Category / Customer / Supplier — 2026-07-05

## 1. Phạm vi đã kiểm tra (liệt kê file)

- `api/src/main/java/com/quiktech/backend/controller/ProductController.java`
- `api/src/main/java/com/quiktech/backend/controller/CategoryController.java`
- `api/src/main/java/com/quiktech/backend/controller/CustomerController.java`
- `api/src/main/java/com/quiktech/backend/controller/SupplierController.java`
- `api/src/main/java/com/quiktech/backend/controller/UnitController.java`
- `api/src/main/java/com/quiktech/backend/service/{ProductService, CategoryService, CustomerService, SupplierService, UnitService, SubscriptionLimitService}.java`
- `api/src/main/java/com/quiktech/backend/repository/{ProductRepository, ProductSpec, CategoryRepository, CustomerRepository, SupplierRepository, UnitRepository, PriceHistoryRepository}.java`
- `api/src/main/java/com/quiktech/backend/entity/{Product, Category, Customer, Supplier, Unit, PriceHistory}.java`
- `api/src/main/java/com/quiktech/backend/dto/request/catalog/{ProductUpsertRequest, CategoryUpsertRequest, UnitUpsertRequest}.java`
- `api/src/main/java/com/quiktech/backend/dto/request/partner/{CustomerUpsertRequest, SupplierUpsertRequest, CustomerPayRequest, SupplierPayRequest}.java`
- `api/src/main/resources/db/migration/V1__initial_schema.sql` (unique index, trigger tsvector — chỉ đối chiếu, schema đã review ở phần 01)
- `api/docs/FEATURES.md` (mục 7, 8, 11, 12, 18, 19), `api/docs/LIFECYCLE.md` (mục 3b), `api/docs/REDIS_CACHE.md`
- `apps/web/docs/PRODUCTS.md` + `apps/web/lib/api.ts` (đối chiếu contract frontend)

> Không có entity ProductVariant, không có logic barcode riêng (chỉ SKU), không có image/upload cho product trong codebase.
> Không có Redis cache cho product/category (`@Cacheable` không được dùng ở đâu; Redis chỉ dùng cho auth-role cache và idempotency — đã review ở phần 02) → mục "cache evict" không áp dụng.
> `SyncChangeLogRepository` tồn tại nhưng **không có service/controller nào sử dụng** — cơ chế sync delta chưa được implement.

## 2. Tổng quan nhận xét

Module catalog/partner có cấu trúc nhất quán: mọi endpoint đều nằm dưới `/api/businesses/{businessId}/...` với `@PreAuthorize` phân biệt member (đọc) và owner/manager (ghi), unique constraint app-level + partial unique index DB (`WHERE deleted_at IS NULL`) cho SKU/code/tên, và soft delete xuyên suốt. Phần search product làm khá tốt: Specification động + custom function `fts_match` dùng GIN index, whitelist sort field chống sort injection, và 2-query batch fetch tránh N+1 có chủ đích.

Tuy nhiên, **pattern IDOR phát hiện ở phần 03 (StoreService) lặp lại nguyên vẹn và trên diện rộng hơn ở cả 5 entity của module này**: mọi thao tác theo id (get/update/delete/setStatus/pay) đều load bằng `findByPublicId(publicId)` mà không đối chiếu entity có thuộc `businessId` trên URL hay không. Khác phần 03 (id số tự tăng, đoán được), ở đây id là UUID ngẫu nhiên nên khó đoán mò — nhưng UUID hoàn toàn có thể bị lộ qua nhân viên cũ, export, URL chia sẻ, và thiết kế local-first (client giữ toàn bộ publicId trong DB local). Kịch bản thực tế: STAFF nghỉ việc ở business B, tự tạo business FREE của mình, rồi dùng URL business của mình + UUID cũ để sửa/xóa sản phẩm, xóa công nợ khách hàng của business B.

Nhóm vấn đề thứ hai xoay quanh **tiền và concurrency**: `Customer/Supplier.pay()` không có lock/optimistic version (4/5 entity khai báo `syncVersion` nhưng thiếu `@Version` — chỉ Product có), Payment bị gán vào "store đầu tiên" tùy ý của business, và xóa khách/NCC còn công nợ không bị chặn. Cuối cùng là một số sai lệch docs–code rõ rệt (audit log cho xóa KH/NCC được docs cam kết nhưng không tồn tại; web docs mô tả endpoint `/api/stores/...` trong khi code dùng `/api/businesses/...`).

## 3. Vấn đề phát hiện

### [CRITICAL] IDOR xuyên tenant trên cả 5 entity: mọi lookup theo publicId không kiểm tra thuộc businessId trên URL
- Vị trí:
  - `ProductService.java:314` (`findProductOrThrow` — dùng bởi get/update/setStatus/delete), `ProductService.java:319` (get detail)
  - `CategoryService.java:64` (update), `CategoryService.java:86` (delete)
  - `CustomerService.java:162` (`findCustomerOrThrow` — dùng bởi get/update/**pay**/delete)
  - `SupplierService.java:162` (`findSupplierOrThrow` — dùng bởi get/update/**pay**/delete)
  - `UnitService.java:64` (update), `UnitService.java:89` (delete)
  - Repository gốc: `ProductRepository.findByPublicId`, `CategoryRepository.findByPublicId`, `CustomerRepository.findByPublicId`, `SupplierRepository.findByPublicId`, `UnitRepository.findByPublicId` — tất cả đều không có điều kiện `businessId`.
- Mô tả: `@PreAuthorize("@businessAccess.isMember/isOwnerOrManager(#businessId, ...)")` chỉ xác nhận caller thuộc business *trên URL*; service sau đó `findBusinessOrThrow(businessId)` (chỉ check tồn tại) rồi load entity **chỉ bằng publicId**, không so sánh `entity.getBusiness().getId()` với `businessId`. Kẻ tấn công là OWNER của business A bất kỳ (tạo miễn phí) có thể gọi `PUT/DELETE /api/businesses/{A}/products/{uuidCủaB}` để sửa/xóa dữ liệu của business B. Đặc biệt nghiêm trọng với `pay()`: `PUT /api/businesses/{A}/customers/{uuidKháchCủaB}/pay` sẽ **trừ công nợ khách hàng của business B**, đồng thời tạo bản ghi `Payment` gắn store của A nhưng customer của B (dữ liệu chéo tenant trong bảng payments). UUID tuy khó đoán nhưng đây là hệ thống local-first — publicId nằm trong DB local của mọi thiết bị từng đăng nhập, nhân viên cũ luôn có sẵn toàn bộ UUID của tenant.
- Tác động: Phá vỡ multi-tenant isolation cho toàn bộ catalog + partner: đọc, sửa, xóa, và thao túng công nợ (tài chính) của tenant khác. Cùng pattern với lỗi CRITICAL ở phần 03 nhưng phạm vi rộng hơn (5 entity × ~4 thao tác).
- Đề xuất: Thay toàn bộ `findByPublicId(publicId)` bằng query scoped `findByBusinessIdAndPublicId(businessId, publicId)` (miss → 404). Với Unit cần thêm nhánh cho system unit: `(business_id = :businessId OR business_id IS NULL)`. Viết test cho từng entity: member business A thao tác trên UUID của business B → expect 404.

### [HIGH] Create/update product cho phép gắn category/unit của tenant khác
- Vị trí: `ProductService.java:122-123` (create), `ProductService.java:168-169` (update), `resolveCategory` :297-301, `resolveUnit` :303-306
- Mô tả: `resolveCategory`/`resolveUnit` tra cứu chỉ bằng `publicId`, không kiểm tra category/unit thuộc business đang thao tác. Product của business A có thể trỏ FK sang category/unit của business B (chỉ cần biết UUID). Response sau đó trả về `categoryName`/`unitName` của B → rò rỉ dữ liệu tenant khác; và khi B xóa category/unit đó, product của A bị ảnh hưởng (xem vấn đề Unit bên dưới). Với Unit, việc chấp nhận unit của tenant khác còn lệch với thiết kế "system unit (business null) + business unit" trong `FEATURES.md` mục 7.
- Tác động: Vi phạm tenant isolation ở đường ghi (không cần sở hữu row của tenant khác vẫn tạo được liên kết chéo tenant), rò rỉ tên category/unit, và tạo phụ thuộc dữ liệu giữa 2 tenant không liên quan.
- Đề xuất: `resolveCategory(businessId, publicId)` → `findByBusinessIdAndPublicId`; `resolveUnit(businessId, publicId)` → query `(business_id = :businessId OR business_id IS NULL) AND public_id = :publicId`.

### [HIGH] Xóa Unit đang được product sử dụng không bị chặn → toàn bộ product dùng unit đó biến mất khỏi API, search có thể 500
- Vị trí: `UnitService.java:89-97` (delete không kiểm tra tham chiếu); `ProductRepository.java` các query `JOIN FETCH p.unit` (INNER JOIN, dòng 22, 30, 33, 36, 41); `Unit.java` `@SQLRestriction("deleted_at IS NULL")`; `ProductService.java:93` (fallback `byId.getOrDefault(p.getId(), p)`)
- Mô tả: `UnitService.delete` soft-delete unit mà không kiểm tra có product nào đang dùng. Vì `Unit` có `@SQLRestriction`, mọi query product `JOIN FETCH p.unit` (inner join) sẽ **loại bỏ luôn các product trỏ tới unit đã xóa** — sản phẩm "bốc hơi" khỏi list, get, findBySku mà không hề bị xóa. Tệ hơn, ở `search()`: query Specification (không join unit) vẫn trả product, nhưng khi hydrate entity, `@ManyToOne` unit (mặc định EAGER) load ra rỗng do SQLRestriction → Hibernate ném `EntityNotFoundException` → **endpoint search trả 500 cho cả trang kết quả**. Chính comment trong `ProductRepository.java` dòng 20-21 thừa nhận rủi ro này nhưng chỉ vá ở tầng đọc, không chặn ở tầng xóa. Category tương tự không được guard khi delete (`CategoryService.java:86-90`) nhưng nhẹ hơn: product dùng LEFT JOIN nên chỉ mất tên danh mục (hiển thị null) — đáng chú ý là `ProductRepository.findByCategoryIdAndDeletedAtIsNull` (dòng 25) tồn tại sẵn cho việc guard này nhưng **không được gọi ở đâu** (dead code).
- Tác động: Một thao tác hợp lệ của OWNER/MANAGER (xóa unit) làm mất khả năng truy cập nguyên một nhóm sản phẩm (kèm tồn kho, lịch sử giá của chúng trên UI) và làm sập endpoint search — phải sửa tay DB để khôi phục.
- Đề xuất: Trong `UnitService.delete` (và `CategoryService.delete`), đếm product tham chiếu còn sống; nếu > 0 → từ chối với lỗi nghiệp vụ rõ ràng (hoặc yêu cầu chuyển product sang unit khác trước). Tận dụng `findByCategoryIdAndDeletedAtIsNull` sẵn có cho category, thêm `countByUnitIdAndDeletedAtIsNull` cho unit.

### [HIGH] pay() không có lock/optimistic version — race làm sai công nợ; 4/5 entity khai báo syncVersion nhưng thiếu @Version nên mãi bằng 0
- Vị trí: `CustomerService.java:100-147` (pay), `SupplierService.java:100-147` (pay); `Customer.java:59`, `Supplier.java:56`, `Category.java:41`, `Unit.java:41` (field `syncVersion` **không có** `@Version` — đối chiếu `Product.java:74-77` có `@Version`)
- Mô tả: `pay()` là check-then-act thuần: đọc `debtBalance`, so sánh với `amount`, trừ rồi save — không `@Version`, không `PESSIMISTIC_WRITE`. Hai request pay đồng thời cùng đọc `debtBalance = 100`, cả hai pass check, cả hai ghi `0` → 2 bản ghi Payment 100đ được tạo, các Order bị cộng `paidAmount` hai lần (vòng phân bổ chạy 2 lần trên cùng danh sách order), trong khi `debtBalance` chỉ giảm 100 → sổ nợ và sổ thanh toán lệch nhau vĩnh viễn. Việc thiếu `@Version` còn có hệ quả thứ hai: cột `sync_version` của Category/Customer/Supplier/Unit **không bao giờ tăng** (luôn 0), vô hiệu hóa thiết kế local-first conflict resolution mà schema đã chuẩn bị (mọi bảng đều có `sync_version` trong `V1__initial_schema.sql`) — chỉ Product hoạt động đúng.
- Tác động: Sai lệch dữ liệu tài chính (double-payment) dưới tải đồng thời (POS nhiều thiết bị là kịch bản chuẩn của sản phẩm); cơ chế sync version chết im lặng trên 4 entity.
- Đề xuất: Thêm `@Version` vào `syncVersion` của 4 entity còn lại (đúng như Product); riêng `pay()` nên load bằng `PESSIMISTIC_WRITE` (tiền bạc, cần serialize) và bọc chung việc trừ nợ + phân bổ order + tạo Payment (đã cùng transaction — chỉ thiếu lock).

### [HIGH] importCsv: khi chạm plan limit giữa file → transaction bị đánh dấu rollback-only, kết quả 500 và mất toàn bộ rows đã "imported"
- Vị trí: `ProductService.java:203-276` (importCsv — một `@Transactional` duy nhất, catch exception per-row tại :266-269), `ProductService.java:252` (gọi `checkProductLimit` trong vòng lặp), `SubscriptionLimitService.java` (mọi method `@Transactional(readOnly = true)`)
- Mô tả: `checkProductLimit` là method `@Transactional` trên bean khác — khi nó ném `SubscriptionLimitExceededException`, transaction interceptor của Spring đánh dấu transaction chung (propagation REQUIRED) là **rollback-only** trước khi exception về tới catch per-row của `importCsv`. Vòng lặp tiếp tục chạy, `imported`/`errors` được đếm bình thường, nhưng khi method kết thúc, commit ném `UnexpectedRollbackException` → client nhận 500 và **không một row nào được lưu**, dù về nghiệp vụ các row trước điểm chạm limit là hợp lệ. Tình huống kích hoạt rất thực tế: business FREE (max 50 products) import file 100 dòng. Tương tự, bất kỳ lỗi DB nào tại flush (vd race với partial unique index `ux_products_business_sku`) cũng phá vỡ ngữ nghĩa "per-row error, phần còn lại vẫn vào".
- Tác động: Tính năng import gãy đúng ở các ca cần nó nhất (file lớn / gần limit); thông điệp lỗi trả về (nếu có) không phản ánh thực tế là toàn bộ đã rollback.
- Đề xuất: Đơn giản nhất: đếm limit **một lần trước vòng lặp** (`count + số row hợp lệ dự kiến`), không gọi `checkProductLimit` per-row qua proxy; khi vượt thì dừng đọc và trả lỗi rõ ràng. Nếu muốn ngữ nghĩa per-row thật sự, tách mỗi row vào `REQUIRES_NEW` (đánh đổi hiệu năng) hoặc validate toàn bộ file trước rồi insert một lần.

### [MEDIUM] importCsv bỏ qua toàn bộ bean validation và có parser CSV ngây thơ
- Vị trí: `ProductService.java:225` (`line.split(",", -1)`), :237-239 (parse giá không check âm), :254-257 (category sai tên bị bỏ qua im lặng, unit chỉ tìm business unit)
- Mô tả: (1) Đường import không đi qua `@Valid ProductUpsertRequest` → chấp nhận `costPrice/sellingPrice/minStockLevel` **âm**, name/description vượt độ dài cột (fail ở DB với thông báo khó hiểu). (2) `split(",")` không hỗ trợ quoted field — tên/mô tả chứa dấu phẩy (rất phổ biến ở dữ liệu tiếng Việt) làm lệch cột, row hỏng hoặc sai dữ liệu im lặng. (3) Unit tra bằng `findByBusinessIdAndNameAndDeletedAtIsNull` → **system unit (Cái, Kg, Lít... — business_id NULL) không dùng được trong CSV**, dù UI tạo product bằng system unit bình thường; export rồi re-import sẽ fail các row này. (4) `categoryName` không khớp → âm thầm set null, không báo trong `errors`. (5) Không giới hạn số dòng/kích thước xử lý — file lớn chạy 2 query/row trong 1 transaction dài (giữ connection + lock). (6) Không có `@Auditable` trong khi `CREATE_PRODUCT` đơn lẻ được audit — import hàng loạt né audit trail.
- Tác động: Dữ liệu bẩn (giá âm phá báo cáo lợi nhuận), hành vi import không nhất quán với UI, lỗ hổng audit, rủi ro chiếm tài nguyên.
- Đề xuất: Validate từng row bằng cùng ràng buộc với `ProductUpsertRequest` (tái dùng Validator), dùng thư viện CSV thật (Apache Commons CSV/OpenCSV), tra unit theo `(businessId OR NULL)`, báo lỗi khi category không khớp, giới hạn số dòng (vd 1000), thêm `@Auditable(action = "IMPORT_PRODUCTS")`.

### [MEDIUM] pay() gán Payment vào "store đầu tiên" tùy ý của business + paymentMethod là chuỗi tự do
- Vị trí: `CustomerService.java:128-131` và `SupplierService.java:128-131` (`storeRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream().findFirst()`), `CustomerPayRequest.java`/`SupplierPayRequest.java` (`String paymentMethod` không validate)
- Mô tả: Business nhiều store: mọi phiếu thu/chi công nợ đều bị gắn vào store đầu tiên mà list trả về (không có ORDER BY → thứ tự không xác định), bất kể người thu tiền đứng ở store nào. `paymentMethod` nhận chuỗi bất kỳ ("CASH", "cash", "abc"...) trong khi codebase có enum `PaymentMethod` — dữ liệu không chuẩn hóa sẽ phá filter/báo cáo thanh toán. Ngoài ra nếu tổng `debtAmount` của các order COMPLETED nhỏ hơn `debtBalance` (đã lệch từ trước), phần `remaining` còn dư sau vòng phân bổ bị bỏ qua im lặng — hai sổ tiếp tục lệch mà không có log/cảnh báo.
- Tác động: Báo cáo thu chi theo store sai; dữ liệu paymentMethod bẩn; lệch sổ nợ tích lũy không được phát hiện.
- Đề xuất: Nhận `storeId` (hoặc `storePublicId`) trong request và validate thuộc business; validate `paymentMethod` bằng enum `PaymentMethod`; log warn khi `remaining > 0` sau phân bổ.

### [MEDIUM] Xóa customer/supplier còn công nợ và xóa product còn tồn kho không bị chặn
- Vị trí: `CustomerService.java:149-154`, `SupplierService.java:149-154`, `ProductService.java:195-201`
- Mô tả: `delete` chỉ set `deletedAt`, không kiểm tra `debtBalance > 0`. Sau khi xóa, khách/NCC biến mất khỏi `findCustomersWithDebt`/`findSuppliersWithDebt` (đều filter `deleted_at IS NULL`) và khỏi danh sách — **khoản nợ biến mất khỏi mọi báo cáo công nợ** dù order/purchase order gốc vẫn ghi `debtAmount`. Tương tự, xóa product không kiểm tra `totalStock > 0` — hàng còn trong kho nhưng sản phẩm không còn tra cứu được (liên quan phần 05: inventory row vẫn tồn tại, dashboard tồn kho có thể vẫn đếm qua materialized view trong khi API product trả 404). Một nhân viên gian lận có quyền MANAGER có thể "xóa nợ" cho khách quen bằng đúng 1 request delete (không audit — xem vấn đề docs bên dưới).
- Tác động: Thất thoát công nợ khỏi tầm nhìn quản lý; lệch giữa tồn kho và catalog.
- Đề xuất: Chặn delete khi `debtBalance != 0` (yêu cầu tất toán trước) và cân nhắc chặn/cảnh báo khi product còn `totalStock > 0`; tối thiểu phải thêm `@Auditable` cho các thao tác delete này.

### [MEDIUM] Docs vs code: FEATURES.md cam kết audit "Xoá khách hàng / nhà cung cấp" nhưng code không audit; Category/Customer/Supplier/Unit hoàn toàn ngoài audit
- Vị trí: `api/docs/FEATURES.md:252-263` (mục 18 liệt kê "Xoá khách hàng / nhà cung cấp", "Thay đổi role thành viên", "Thay đổi gói subscription" được audit); thực tế `@Auditable` chỉ có ở Order/Inventory/Product/PurchaseOrder (xác nhận bằng grep toàn bộ `service/`); `CustomerService`/`SupplierService`/`CategoryService`/`UnitService` không có annotation nào
- Mô tả: Mục 18 và mục 19 của chính FEATURES.md mâu thuẫn nhau — mục 19 liệt kê đúng những gì code làm, mục 18 hứa nhiều hơn. Các thao tác nhạy cảm về tiền (pay công nợ, xóa KH/NCC còn nợ) và catalog (xóa category/unit gây hệ quả dây chuyền như trên) không để lại dấu vết trong `audit_logs`. Ngoài ra `ProductService.setStatus` và `importCsv` cũng không audit dù cùng nhóm thao tác ghi với create/update/delete.
- Tác động: Sai lệch tài liệu; mất khả năng truy vết cho đúng nhóm thao tác dễ bị lạm dụng nhất của module này.
- Đề xuất: Thêm `@Auditable` cho pay/delete của Customer/Supplier, delete của Category/Unit, setStatus/import của Product — hoặc sửa mục 18 FEATURES.md về đúng hiện trạng (khuyến nghị làm cả hai: thêm code trước).

### [MEDIUM] Docs vs code: web PRODUCTS.md mô tả endpoint `/api/stores/{id}/...` trong khi backend (và chính web code) dùng `/api/businesses/{id}/...`; FEATURES.md dùng sai scope "cửa hàng"
- Vị trí: `apps/web/docs/PRODUCTS.md:61-124` (toàn bộ bảng API Endpoints ghi `/api/stores/{id}/products|categories|units`); thực tế `apps/web/lib/api.ts:206,445-558` gọi `businessUrl()` → `/api/businesses/{businessId}/...` khớp backend; `api/docs/FEATURES.md:108,150` ("SKU unique trong cùng cửa hàng", "Mã khách hàng unique trong cùng cửa hàng") — thực tế unique theo **business** (`ux_products_business_sku`, `ux_customers_business_code`); FEATURES.md mục 11 nói tìm kiếm khách hàng bằng "full-text search" — thực tế `CustomerRepository.searchCustomers`/`searchSuppliers` dùng `ILIKE '%q%'` (cột `search_vector` + GIN index có sẵn trong schema nhưng không được dùng)
- Mô tả: Ba điểm lệch độc lập: (1) tài liệu frontend mô tả sai URL scheme (stale — dễ dẫn dev mới viết sai); (2) tài liệu backend mô tả sai scope uniqueness — với business nhiều store, mọi store dùng chung catalog/khách hàng, khác với điều docs ngụ ý; (3) claim FTS cho customer nhưng dùng ILIKE hai đầu wildcard — không dùng được index thường (GIN tsvector bị bỏ phí), đồng thời `%`/`_` trong `q` không được escape (người dùng gõ "%" trả toàn bộ bảng).
- Tác động: Nhầm lẫn contract giữa các team; hiệu năng search customer/supplier suy giảm tuyến tính theo dữ liệu; hành vi wildcard ngoài ý muốn.
- Đề xuất: Cập nhật PRODUCTS.md và FEATURES.md về đúng hiện trạng; chuyển search customer/supplier sang `fts_match` như product (hạ tầng đã có sẵn: trigger tsvector + GIN index trong V1).

### [LOW] Soft delete và setStatus không cập nhật trường sync (lastModifiedAt/lastModifiedByUser)
- Vị trí: `ProductService.java:182-191` (setStatus chỉ set `updatedAt`), `ProductService.java:195-201`, `CategoryService.java:86-90`, `CustomerService.java:149-154`, `SupplierService.java:149-154`, `UnitService.java:89-97` (delete chỉ set `deletedAt`)
- Mô tả: Update thường có set `lastModifiedAt`/`lastModifiedByUser` nhưng delete/setStatus thì không. Kết hợp `@SQLRestriction` ẩn hàng đã xóa khỏi mọi query, client local-first sẽ không có cách nhận biết bản ghi bị xóa khi sync delta được implement (SyncChangeLog hiện chưa có code ghi). Hiện tại chưa gây lỗi vì sync chưa chạy, nhưng là bom hẹn giờ cho đúng tính năng mà schema được thiết kế phục vụ.
- Tác động: Chưa ảnh hưởng runtime; sẽ chặn tính năng sync sau này.
- Đề xuất: Chuẩn hóa: mọi mutation (kể cả soft delete/status) đều set `lastModifiedAt`/`lastModifiedByUser`, và ghi `SyncChangeLog` khi implement sync.

### [LOW] Validation còn hở: minStockLevel âm, không trim/normalize input, unit trùng tên system unit
- Vị trí: `ProductUpsertRequest.java` (`minStockLevel` chỉ `@NotNull`, thiếu `@Min(0)`); các service create/update không `trim()` sku/name/code trước khi check unique (`" SKU01"` và `"SKU01"` cùng tồn tại được); `UnitService.java:44-46` chỉ check trùng tên với business unit, không check system unit (`ux_units_business_name` dùng `COALESCE(business_id, 0)` nên DB cũng cho phép) → dropdown unit hiện 2 mục "Cái"
- Tác động: Dữ liệu bẩn mức nhẹ, cảnh báo tồn kho sai khi minStockLevel âm, UX nhầm lẫn.
- Đề xuất: Thêm `@Min(0)`; trim + chuẩn hóa khoảng trắng trước khi lưu/so sánh; check trùng tên với cả system units trong `UnitService.create/update`.

### [LOW] Response DTO lộ id nội bộ (auto-increment) của entity và business
- Vị trí: `ProductService.toResponse` (`p.getId()`, `p.getBusiness().getId()`, `category.getId()`, `unit.getId()`), tương tự `CategoryResponse`, `CustomerResponse`, `SupplierResponse`, `UnitResponse`
- Mô tả: API đã có publicId (UUID) làm định danh công khai nhưng vẫn trả kèm id số tự tăng. Phần 03 đã cho thấy id số đoán được là nguyên liệu cho IDOR (StoreMember); việc phơi id nội bộ ở đây cho attacker thông tin về quy mô dữ liệu và tọa độ để khai thác các endpoint khác còn dùng id số.
- Tác động: Tăng bề mặt tấn công gián tiếp, lộ metric kinh doanh (số lượng bản ghi qua khoảng cách id).
- Đề xuất: Loại id nội bộ khỏi các response DTO, chỉ giữ publicId (cần phối hợp frontend — `products-table.tsx` đang route bằng `product.id`).

### [LOW] N+1 nhỏ ở product detail và dead code
- Vị trí: `ProductService.toDetailResponse` (`h.getChangedBy().getUsername()` — mỗi dòng price history lazy-load 1 user; `findByPublicIdWithPriceHistories` chỉ fetch histories, không fetch changedBy/unit); `ProductRepository.java:25` (`findByCategoryIdAndDeletedAtIsNull` không được sử dụng — xem vấn đề Unit/Category ở trên); các `@ManyToOne` mặc định EAGER trong khi comment trong repo nói về "lazy load" (không nhất quán nhận thức, may là các query chính đều JOIN FETCH)
- Tác động: Hiệu năng nhẹ trên product detail có nhiều lịch sử giá; code thừa gây hiểu nhầm.
- Đề xuất: Thêm `JOIN FETCH ph.changedBy` (và unit) vào `findByPublicIdWithPriceHistories`; dùng hoặc ghi chú rõ `findByCategoryIdAndDeletedAtIsNull` khi fix guard category delete.

## 4. Điểm tốt

- `@PreAuthorize` phủ 100% endpoint của module, phân quyền đọc (member) / ghi (owner/manager) nhất quán.
- Partial unique index DB (`WHERE deleted_at IS NULL`) cho SKU/code/tên category/unit — backstop đúng cho check-then-act, và soft-deleted row không chặn tái sử dụng mã (đúng ngữ nghĩa soft delete).
- Search product làm bài bản: Specification động (tránh generic plan), custom function `fts_match` dùng GIN index, whitelist sort field (`name`/`updatedAt`) trong controller nên **không có sort injection**, page size bị chặn `@Max(100)`.
- Price history ghi tự động, immutable, chỉ khi giá thực sự đổi (so sánh `compareTo`), kèm log.
- Bean validation ở DTO khá đầy đủ (DecimalMin cho giá, Pattern cho phone, Email, Size mọi trường text) — đường JSON không nhận giá âm.
- `pay()` có chặn `amount > debtBalance` và phân bổ nợ vào order cũ nhất trước — logic nghiệp vụ đúng hướng, chỉ thiếu lock.
- Unit hệ thống được bảo vệ khỏi sửa/xóa bởi tenant (`ForbiddenException` khi `business == null`).
