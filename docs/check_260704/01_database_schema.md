# Đánh giá Database & Schema — 2026-07-04 (cập nhật trạng thái 2026-07-08)

> Reviewer: Backend QA — phạm vi Database & Schema (Java 21, Spring Boot 3.5, PostgreSQL 16, Hibernate 6, Flyway).
> Cấu hình quan trọng: `spring.jpa.hibernate.ddl-auto=validate` (application.properties:7) — schema thật do Flyway quyết định, entity chỉ được validate (tồn tại bảng/cột + JDBC type; **không** validate nullable, length, unique, index).
>
> **Cập nhật 2026-07-08:** Toàn bộ vấn đề đã được xử lý qua các commit `6aed30d`, `84ac478`, `e149baf`, `1f28701`, `4559682` — mỗi mục bên dưới có dòng **Trạng thái**. Do DB dev chạy Docker (down/up tự do), các fix schema được **sửa trực tiếp vào V1/V3** thay vì tạo V7; chấp nhận Flyway checksum mismatch với DB cũ.

## 1. Phạm vi đã kiểm tra (liệt kê file)

**Migrations (6 file):**
- `src\main\resources\db\migration\V1__initial_schema.sql` (761 dòng — toàn bộ schema, index, CHECK, MV, trigger FTS)
- `src\main\resources\db\migration\V2__subscription_invoice_bank_transfer.sql`
- `src\main\resources\db\migration\V3__subscription_downgrade.sql`
- `src\main\resources\db\migration\V4__add_previous_quantity_to_inventory_transactions.sql`
- `src\main\resources\db\migration\V5__refresh_tokens.sql`
- `src\main\resources\db\migration\V6__audit_logs.sql`

**Entities (28 file):** AuditLog, Business, BusinessMember, Category, Customer, Inventory, InventoryTransaction, Order, OrderItem, Payment, PriceHistory, Product, PurchaseOrder, PurchaseOrderItem, RefreshToken, ReturnOrder, ReturnOrderItem, Role, Store, StoreMember, Subscription, SubscriptionInvoice, Supplier, SyncChangeLog, Unit, User, UserRole, Warehouse.

**Enums (11 file):** BillingCycle, DiscountType, InvoiceStatus, OrderStatus, PaymentMethod, PlanLimits, PlanPricing, PurchaseOrderStatus, RoleName, SubscriptionPlan, SubscriptionStatus.

**Docs tham khảo:** `docs\database\DATABASE_SCHEMA.md`, `SCHEMA_REVIEW_1.1.md` (đã ghi nhận trước đó: debt_balance không có enforcement DB, sync write contract chưa chuẩn hóa — các vấn đề này vẫn còn nguyên, không lặp lại chi tiết ở đây).

**Kiểm tra chéo (đối chiếu):** `PaymentService.java`, `ProductRepository.java`, `DashboardService.java`, `DashboardRefreshScheduler.java`, `application.properties`.

## 2. Tổng quan nhận xét

Schema V1 được thiết kế khá bài bản: tiền tệ dùng `NUMERIC(15,2)`/`BigDecimal` toàn bộ, thời gian dùng `TIMESTAMPTZ` + JDBC timezone UTC, enum lưu STRING (không có chỗ nào dùng ordinal), unique constraint đều scoped theo tenant (`business_id`/`store_id`) và dùng partial index `WHERE deleted_at IS NULL` đúng với soft delete. CHECK constraints phủ khá đầy đủ enum và giá trị âm.

Tuy nhiên có 3 nhóm rủi ro chính (tại thời điểm review 2026-07-04): (1) **enum Java lệch với CHECK constraint DB** ở `payment_method` — có thể gây lỗi 500 với input hợp lệ từ client; (2) **hàng loạt index khai báo trong entity không tồn tại trong DB** vì `ddl-auto=validate` không tạo index — đặc biệt `sync_change_log` không có index nào; (3) **quy ước soft delete / audit column / sync fields không nhất quán** giữa các entity, dễ sinh bug âm thầm. → **Cả 3 nhóm đã được xử lý** (chi tiết từng mục ở phần 3).

## 3. Vấn đề phát hiện

### [CRITICAL] Enum `PaymentMethod` (6 giá trị) lệch với CHECK constraint `payments` (2 giá trị)
- **Vị trí:** `entity\enums\PaymentMethod.java:3-5` ↔ `V1__initial_schema.sql:673` ↔ `PaymentService.java:110,157,192` ↔ `dto\request\payment\PaymentCreateRequest.java`
- **Mô tả:** Java enum có `CASH, BANK_TRANSFER, CREDIT_CARD, DEBIT_CARD, MOBILE_PAYMENT, OTHER`, nhưng DB có `chk_payments_method CHECK (payment_method IN ('CASH', 'BANK_TRANSFER'))`. `PaymentCreateRequest` nhận `PaymentMethod` từ client và `PaymentService` ghi thẳng `String.valueOf(request.paymentMethod())` vào cột.
- **Tác động:** Client gửi `CREDIT_CARD` (hợp lệ theo API contract) → INSERT vi phạm CHECK → PSQLException → HTTP 500 lúc runtime. Ngoài ra `orders.payment_method` và `purchase_orders.payment_method` lại **không có CHECK nào** → cùng một khái niệm nhưng 3 mức enforcement khác nhau.
- **Đề xuất:** Chọn một nguồn sự thật: hoặc thu hẹp enum Java về 2 giá trị đang hỗ trợ, hoặc migration mở rộng CHECK. Đồng thời thêm CHECK tương tự cho `orders`/`purchase_orders` (hoặc bỏ hẳn để thống nhất).
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`6aed30d`) — `chk_payments_method` mở rộng thành 6 giá trị khớp enum Java: `CASH, BANK_TRANSFER, CREDIT_CARD, DEBIT_CARD, MOBILE_PAYMENT, OTHER`. ⚠️ Còn mở: `orders.payment_method` / `purchase_orders.payment_method` vẫn chưa có CHECK (String tự do, docs API PURCHASE_ORDER.md còn ghi giá trị `TRANSFER` không thuộc enum).

### [HIGH] Index khai báo trong entity không tồn tại trong DB — `sync_change_log` hoàn toàn không có index
- **Vị trí:** `SyncChangeLog.java:9-12` (khai báo `idx_sync_log_store_id`, `idx_sync_log_version`) ↔ `V1__initial_schema.sql:541-551` (không tạo index nào cho bảng này); tương tự: `Payment.java:11` (`idx_payments_store_id` — V1 chỉ có index customer/supplier), `OrderItem.java:15` (`idx_order_items_store_id`), `InventoryTransaction.java:11` (`idx_inventory_tx_warehouse_id`), `PurchaseOrder.java:16` (`idx_purchase_orders_warehouse_id`), `ReturnOrderItem.java:10` (`idx_return_order_items_product_id`), `Store.java:10-11` (`idx_stores_name`, `idx_stores_deleted_at`), `Customer.java:13`/`Supplier.java:13` (`idx_customers_code`/`idx_suppliers_code`), `User.java:13-16`, `SubscriptionInvoice.java:14`
- **Mô tả:** Với `ddl-auto=validate`, `@Index`/`@Table(indexes=...)` **không bao giờ được tạo** và cũng không được validate. Mọi index chỉ tồn tại nếu có trong migration. Kết quả: entity "tưởng" có index nhưng DB không có. Nghiêm trọng nhất là `sync_change_log` — bảng nền tảng cho delta sync (query điển hình `WHERE store_id = ? AND sync_version > ?`) sẽ seq scan toàn bảng, và bảng này chỉ tăng không giảm.
- **Tác động:** Suy giảm hiệu năng tuyến tính theo dữ liệu: sync chậm dần, truy vấn payments theo store seq scan, lookup inventory_transactions theo warehouse seq scan.
- **Đề xuất:** Migration V7 bổ sung tối thiểu: `sync_change_log(store_id, sync_version)`, `payments(store_id, created_at DESC)`, `order_items(store_id)`, `inventory_transactions(warehouse_id)`, `purchase_orders(warehouse_id)`, `return_order_items(product_id)`, và `inventory(store_id)` (cột này thậm chí không được khai báo index ở cả 2 phía). Về lâu dài: coi migration là nguồn sự thật duy nhất, xóa hoặc đồng bộ các `@Index` trong entity để tránh hiểu nhầm.
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** — (`6aed30d`) V1 bổ sung đủ 7 index đề xuất: `idx_sync_log_store_version`, `idx_payments_store_created`, `idx_order_items_store_id`, `idx_inventory_tx_warehouse_id`, `idx_purchase_orders_warehouse_id`, `idx_return_order_items_product_id`, `idx_inventory_store_id`. (`1f28701`) Toàn bộ `@Index` metadata trong entity đã đồng bộ với tên/cột index thật trong migration (xóa các index ảo như `idx_stores_name`, `idx_users_*`, `idx_customers_code`...).

### [HIGH] `inventory` UNIQUE (product_id, warehouse_id) không partial dù bảng có soft delete
- **Vị trí:** `V1__initial_schema.sql:263` (`ux_inventory_product_warehouse UNIQUE (product_id, warehouse_id)`) ↔ `Inventory.java:15` (`@SQLRestriction("deleted_at IS NULL")`), `Inventory.java:68-69` (`deletedAt`)
- **Mô tả:** Tất cả bảng soft-delete khác đều dùng partial unique index `WHERE deleted_at IS NULL` (V1:623-631), riêng `inventory` dùng UNIQUE constraint tuyệt đối. Khi một dòng inventory bị soft delete, Hibernate không "nhìn thấy" nó (do `@SQLRestriction`), nhưng dòng chết vẫn chiếm chỗ trong unique constraint.
- **Tác động:** Soft delete một inventory rồi tạo lại record cho cùng (product, warehouse) → unique violation mà tầng ứng dụng không thể giải thích được (record "không tồn tại" theo góc nhìn JPA). Kịch bản thực tế: xóa kho hàng khỏi sản phẩm rồi nhập lại hàng.
- **Đề xuất:** Migration: drop constraint, thay bằng `CREATE UNIQUE INDEX ux_inventory_product_warehouse ON inventory(product_id, warehouse_id) WHERE deleted_at IS NULL;` (giống pattern các bảng khác).
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`6aed30d`) — V1 dùng partial unique index `ux_inventory_product_warehouse ... WHERE deleted_at IS NULL` đúng pattern các bảng soft-delete khác.

### [HIGH] V6 `DROP TABLE audit_logs CASCADE` — hủy toàn bộ dữ liệu audit
- **Vị trí:** `V6__audit_logs.sql:1`
- **Mô tả:** Migration V6 redesign bảng audit_logs bằng cách DROP + CREATE thay vì ALTER/migrate data. Toàn bộ audit log tích lũy từ V1 (bao gồm `old_data`/`new_data` JSONB, `performed_by`) bị xóa vĩnh viễn ở mọi môi trường đã chạy V1.
- **Tác động:** Mất dữ liệu forensic/compliance không thể khôi phục — với hệ thống POS có công nợ tài chính, audit trail là dữ liệu pháp lý. Nếu V6 đã chạy trên production thì không sửa được nữa, nhưng đây là anti-pattern cần cấm về sau. Ghi chú thêm: schema mới cũng bỏ các composite index `(business_id, created_at DESC)` của V1, thay bằng index đơn cột — query "audit của business X theo thời gian" kém hiệu quả hơn.
- **Đề xuất:** Quy ước team: migration không bao giờ DROP bảng có dữ liệu nghiệp vụ; nếu redesign thì `ALTER` + `UPDATE` migrate data hoặc rename bảng cũ thành `_archive`. Cân nhắc V7 thêm lại composite index `(business_id, created_at DESC)`, `(store_id, created_at DESC)` cho audit_logs.
- **Trạng thái (2026-07-08):** ⚠️ **Không đảo ngược được** (chưa có production nên không mất dữ liệu thật). V6 hiện đã có composite index `(business_id, created_at DESC)`, `(store_id, created_at DESC)`, `(user_id, created_at DESC)`, `(entity_type, entity_id)`. Convention "không DROP bảng dữ liệu nghiệp vụ" cần được ghi vào tài liệu quy trình team — chưa làm.

### [MEDIUM] `updated_at` không được cập nhật tự động ở đa số entity
- **Vị trí:** Có `@PreUpdate`: `Business.java:47`, `Store.java:55`, `BusinessMember.java:70`, `StoreMember.java:76`, `Subscription.java:78`, `SubscriptionInvoice.java:81`, `Product.java:105`. **Thiếu** `@PreUpdate`/`@UpdateTimestamp`: User, UserRole, Category, Customer, Supplier, Warehouse, Inventory, Order, OrderItem, PurchaseOrder, ReturnOrder, Payment, InventoryTransaction.
- **Mô tả:** DB `DEFAULT now()` chỉ áp dụng khi INSERT. Với UPDATE qua JPA, Hibernate ghi lại giá trị `updatedAt` cũ trong entity → cột đứng yên mãi ở thời điểm tạo. `lastModifiedAt` (sync field) cũng phụ thuộc hoàn toàn vào việc service nhớ set thủ công.
- **Tác động:** `updated_at` sai lệch trên phần lớn bảng nghiệp vụ (orders, customers, inventory...) → báo cáo, debug, và đặc biệt sync delta dựa trên timestamp không tin cậy được.
- **Đề xuất:** Chuẩn hóa bằng `@UpdateTimestamp`/`@CreationTimestamp` của Hibernate hoặc một `@MappedSuperclass`/EntityListener chung cho toàn bộ entity thay vì copy-paste `@PreUpdate` từng nơi.
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`e149baf`) — thêm `@PreUpdate` cho 12 entity còn thiếu (theo house pattern hiện có, không refactor sang base class để giữ thay đổi tối thiểu). Việc gom về `@MappedSuperclass` để backlog nếu muốn.

### [MEDIUM] Nhiều cột trạng thái dùng `String` tự do dù đã có enum + CHECK constraint
- **Vị trí:** `InventoryTransaction.java:38` (`type` String, comment "IN, OUT..."), `ReturnOrder.java:42,51` (`status`, `refundMethod` String), `Order.java:77` / `PurchaseOrder.java:61` / `Payment.java:42` (`paymentMethod` String), `SyncChangeLog.java:35` (`operation` String)
- **Mô tả:** Project đã có pattern chuẩn (`OrderStatus`, `PurchaseOrderStatus`, `DiscountType` dùng `@Enumerated(STRING)`), nhưng các cột trên lại dùng String thuần. DB có CHECK (V1:661-687) nhưng typo phía Java chỉ bị phát hiện lúc INSERT runtime, và `ReturnOrder.status`/`refundMethod` không có enum Java nào tương ứng.
- **Tác động:** Lỗi chính tả/giá trị mới thêm vào Java mà quên sửa CHECK → PSQLException runtime (giống vấn đề CRITICAL ở trên nhưng ở phạm vi khác); so sánh status bằng string literal rải rác trong service.
- **Đề xuất:** Tạo enum `ReturnOrderStatus`, `RefundMethod`, `InventoryTransactionType`, `SyncOperation` và chuyển các field sang `@Enumerated(EnumType.STRING)`. Mỗi lần thêm giá trị enum phải kèm migration sửa CHECK — nên ghi vào convention.
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`84ac478`) — tạo đủ 4 enum `ReturnOrderStatus`, `RefundMethod`, `InventoryTransactionType`, `SyncOperation`; các field `ReturnOrder.status/refundMethod`, `InventoryTransaction.type`, `SyncChangeLog.operation` chuyển sang `@Enumerated(STRING)`; service/repository cập nhật theo. Riêng `paymentMethod` trên `Order`/`PurchaseOrder` vẫn là String (xem mục CRITICAL — phần còn mở).
- **Phát hiện thêm khi enum hóa** (`4559682`): quy ước dấu `quantity` của `inventory_transactions` trộn 2 semantic — `IN`/`OUT` luôn dương, nhưng `TRANSFER`/`ADJUSTMENT` ghi delta có dấu (chân xuất transfer và điều chỉnh giảm là **số âm**), trong khi CHECK cũ là `quantity > 0` → mọi lệnh chuyển kho và điều chỉnh giảm bị DB reject (500). Đã nới thành `chk_inv_tx_qty CHECK (quantity <> 0)` + ghi rõ quy ước dấu vào Javadoc `InventoryTransactionType` (không được SUM(quantity) trộn lẫn type khi báo cáo).

### [MEDIUM] `@Version` đặt trên `syncVersion` không nhất quán giữa các entity
- **Vị trí:** Có `@Version`: `Product.java:74`, `Inventory.java:47`, `Order.java:86`, `OrderItem.java:63`, `PurchaseOrder.java:70`, `ReturnOrder.java:60`, `Payment.java:51`. Không có: `Category.java:40`, `Customer.java:58`, `Supplier.java:55`, `Unit.java:40`, `Warehouse.java:44`, `BusinessMember.java:47`, `StoreMember.java:50`.
- **Mô tả:** Cùng một cột `sync_version` nhưng nửa số entity dùng làm optimistic lock (Hibernate tự tăng mỗi UPDATE), nửa còn lại là field thường (service phải tự tăng). Overload cột sync làm version lock cũng trộn 2 semantic: version tăng cả với thay đổi không cần sync.
- **Tác động:** Category/Customer/Supplier... không có optimistic lock → lost update khi 2 user sửa đồng thời; đồng thời quy tắc bump `sync_version` khác nhau giữa các bảng khiến delta sync (so sánh version) hành xử không đồng nhất. (SCHEMA_REVIEW_1.1 đã cảnh báo "sync write contract" — vẫn chưa được giải quyết.)
- **Đề xuất:** Quyết định một convention: hoặc tất cả bảng sync đều `@Version` trên `sync_version`, hoặc tách `version` (lock) và `sync_version` (sync) thành 2 cột.
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`e149baf`) — chọn convention "tất cả bảng sync đều `@Version` trên `sync_version`": thêm `@Version` cho 7 entity còn thiếu (Category, Customer, Supplier, Unit, Warehouse, BusinessMember, StoreMember).

### [MEDIUM] Soft delete không nhất quán: thiếu `@SQLRestriction`, cha-con lệch nhau
- **Vị trí:** `User.java`, `UserRole.java`, `Store.java`, `Business.java` (có `deleted_at` nhưng **không** có `@SQLRestriction`); `Order.java` (không có `deleted_at`) ↔ `OrderItem.java:17,79-80` (có `deleted_at` + `@SQLRestriction`) kết hợp `Order.java:116` (`cascade = ALL, orphanRemoval = true`)
- **Mô tả:** (a) 4 entity có cột soft delete nhưng mọi query phải tự nhớ lọc `deleted_at IS NULL` — trái với pattern các entity còn lại. (b) `orders` là bảng immutable (đúng thiết kế) nhưng `order_items` lại soft-delete được, đồng thời `orphanRemoval = true` sẽ **DELETE vật lý** item khi bị remove khỏi collection — bypass hoàn toàn cơ chế soft delete.
- **Tác động:** (a) Rò rỉ dữ liệu đã xóa mềm vào kết quả query (đặc biệt Store/User trong luồng auth). (b) Mất dòng lịch sử item của đơn hàng đã có giao dịch tài chính.
- **Đề xuất:** Thêm `@SQLRestriction` cho 4 entity trên (hoặc bỏ `deleted_at` nếu không dùng); với `order_items` chọn một cơ chế duy nhất — nếu giữ soft delete thì bỏ `orphanRemoval`.
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`e149baf`) — thêm `@SQLRestriction("deleted_at IS NULL")` cho User, UserRole, Store, Business; bỏ `orphanRemoval = true` ở `Order.items` và `PurchaseOrder.items` (giữ soft delete, không hard-delete item).

### [MEDIUM] `return_order_items` và `purchase_order_items` thiếu sync fields — local-first không phủ đủ
- **Vị trí:** `V1__initial_schema.sql:421-432, 466-478`; `ReturnOrderItem.java` (không có public_id/sync_version/last_modified_*/audit columns), `PurchaseOrderItem.java` (chỉ có deleted_at)
- **Mô tả:** `order_items` có đầy đủ bộ local-first (public_id, sync_version, last_modified_*), nhưng 2 bảng item còn lại thì không, dù cha của chúng (`return_orders`, `purchase_orders`) có đầy đủ. `return_order_items` thậm chí không có cả `created_at`.
- **Tác động:** Không thể sync delta/định danh cross-device cho item của đơn trả hàng và đơn nhập — client offline tạo return order sẽ không đối chiếu item được; mất tính truy vết thời gian trên `return_order_items`.
- **Đề xuất:** Nếu roadmap local-first còn hiệu lực: migration bổ sung `public_id UUID UNIQUE`, `sync_version`, `created_at` cho 2 bảng này (đồng bộ với entity). Nếu không, ghi rõ vào docs rằng returns/PO chỉ sync theo aggregate cha.
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`84ac478`) — cả 2 bảng có đủ bộ sync fields: `public_id UUID NOT NULL UNIQUE`, `sync_version`, `last_modified_at/by_user/by_device`, `deleted_at` + FK `last_modified_by_user → users`; entity và service (set publicId + lastModifiedByUser khi tạo item) cập nhật theo. Lưu ý: `created_at` riêng vẫn không thêm — `last_modified_at` (DEFAULT now()) đảm nhận truy vết thời gian.

### [LOW] Cột FK phụ chưa có index
- **Vị trí:** `V1__initial_schema.sql` — các FK `created_by`, `last_modified_by_user` (hầu hết bảng), `price_history.changed_by`, `user_roles.granted_by`, `subscription_invoices.confirmed_by` (V2 có partial index — tốt)
- **Mô tả:** PostgreSQL không tự tạo index cho FK. Các cột này ít được query trực tiếp nên chấp nhận được, nhưng sẽ chậm khi cần "mọi bản ghi user X đã tạo" hoặc khi kiểm tra ràng buộc lúc xóa user.
- **Tác động:** Thấp ở quy mô hiện tại; users không bao giờ bị hard delete nên FK check ngược ít xảy ra.
- **Đề xuất:** Chỉ thêm index khi có query thực tế; không cần làm ngay.
- **Trạng thái (2026-07-08):** ⏸ **Giữ nguyên theo đề xuất** — chưa thêm index cho FK phụ (`created_by`, `last_modified_by_user`...), chờ có query thực tế.

### [LOW] `subscriptions.pending_plan` / `pending_billing_cycle` (V3) không có CHECK constraint
- **Vị trí:** `V3__subscription_downgrade.sql:2-4` ↔ `V1__initial_schema.sql:646,648` (cột `plan`, `billing_cycle` gốc đều có CHECK)
- **Mô tả:** Cột mới thêm cùng domain giá trị nhưng không kèm CHECK như cột gốc.
- **Tác động:** Dữ liệu rác có thể lọt vào (dù entity dùng `@Enumerated(STRING)` nên rủi ro chủ yếu từ SQL thủ công).
- **Đề xuất:** V7: `ADD CONSTRAINT chk_subscriptions_pending_plan CHECK (pending_plan IS NULL OR pending_plan IN ('FREE','BASIC','PRO'))`, tương tự cho cycle.
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`6aed30d`) — V3 bổ sung `chk_subscriptions_pending_plan` và `chk_subscriptions_pending_cycle` đúng như đề xuất.

### [LOW] Drift entity ↔ DB không bị `validate` phát hiện (type/length/unique)
- **Vị trí:** `Role.java:25-26` (`description` String → varchar(255)) ↔ `V1:27` (`TEXT`); `User.java:13-16` (`@Index(unique=true)` cho username/email — trùng lặp với UNIQUE constraint V1:13-14); tên index entity lệch tên migration hàng loạt (vd `idx_subscription_invoices_business_id` vs `idx_sub_invoices_business_id`, `idx_price_history_business_id` vs `idx_price_history_business_created`)
- **Mô tả:** Hibernate validate chỉ so bảng/cột/JDBC type nên các lệch này không gây lỗi runtime, nhưng entity đang mô tả sai schema thật.
- **Tác động:** Gây hiểu nhầm khi dev đọc entity; nếu sau này ai đó bật `ddl-auto=update` ở môi trường dev sẽ sinh index/constraint trùng lặp.
- **Đề xuất:** Đồng bộ metadata entity với migration một lần (ưu tiên thấp), giữ nguyên tắc "migration là nguồn sự thật".
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`1f28701`) — `Role.description` chuyển sang `columnDefinition = "TEXT"`; bỏ `@Index(unique=true)` trùng lặp ở User; toàn bộ tên `@Index` đồng bộ với tên index migration (idx_sub_invoices_*, idx_price_history_business_created, idx_orders_store_created...).

### [LOW] FTS dùng config `'simple'` — không xử lý dấu tiếng Việt
- **Vị trí:** `V1__initial_schema.sql:729-756`
- **Mô tả:** `to_tsvector('simple', ...)` không unaccent — tìm "ca phe" sẽ không khớp "cà phê".
- **Tác động:** Chất lượng tìm kiếm sản phẩm/khách hàng kém với dữ liệu tiếng Việt (đúng đối tượng người dùng chính).
- **Đề xuất:** Migration thêm extension `unaccent` và đổi trigger thành `to_tsvector('simple', unaccent(...))` + query dùng `unaccent` tương ứng.
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`84ac478`) — V1 bật `CREATE EXTENSION IF NOT EXISTS unaccent`, cả 2 trigger tsvector wrap `unaccent(...)`, phía query `fts_match` (CustomFunctions) đổi thành `plainto_tsquery('simple', unaccent(?))`.

### [LOW] `chk_payments_reference` cho phép cả `customer_id` và `supplier_id` cùng NULL
- **Vị trí:** `V1__initial_schema.sql:674`
- **Mô tả:** CHECK chỉ cấm cả hai cùng NOT NULL; payment "mồ côi" (không gắn với ai) vẫn hợp lệ.
- **Tác động:** Nếu nghiệp vụ luôn yêu cầu payment thuộc về customer hoặc supplier thì đây là lỗ hổng toàn vẹn nhỏ.
- **Đề xuất:** Nếu đúng nghiệp vụ, siết thành `CHECK ((customer_id IS NULL) <> (supplier_id IS NULL))`.
- **Trạng thái (2026-07-08):** ⏸ **Giữ nguyên có chủ đích** — nghiệp vụ cho phép payment không gắn đối tác (thu/chi nội bộ, khách lẻ walk-in); docs API PAYMENT.md đã mô tả rõ case "cả hai null". Không siết CHECK.

### [LOW] Comment scope của `ROLE_OWNER` mâu thuẫn giữa code và schema
- **Vị trí:** `entity\enums\RoleName.java:5` (ghi OWNER là store-scoped) ↔ `V1__initial_schema.sql:106-107` (OWNER là business-scoped, `business_id NOT NULL`)
- **Mô tả:** Hai comment mô tả ngược nhau về scope của OWNER.
- **Tác động:** Gây nhầm lẫn khi dev mới implement authorization.
- **Đề xuất:** Sửa comment trong `RoleName.java` cho khớp thiết kế thật (OWNER gắn business).
- **Trạng thái (2026-07-08):** ✅ **Đã xử lý** (`1f28701`) — comment `RoleName.java` sửa lại đúng 3 cấp scope: SUPER_ADMIN/SUPPORT global, OWNER business-scoped, MANAGER/STAFF store-scoped.

## 4. Điểm tốt

- **Tiền tệ chuẩn mực:** 100% cột tiền/số lượng dùng `NUMERIC(15,2)` ↔ `BigDecimal`, không có float/double ở bất kỳ đâu (kể cả `PlanPricing`).
- **Timezone an toàn:** `TIMESTAMPTZ` toàn bộ + `hibernate.jdbc.time_zone=UTC` + `Instant` phía Java.
- **Enum lưu STRING:** mọi `@Enumerated` đều `EnumType.STRING`, không có chỗ nào dùng ordinal.
- **Multi-tenant unique đúng cách:** không có unique toàn cục nào trên dữ liệu nghiệp vụ — `(business_id, sku)`, `(business_id, code)`, `(store_id, order_code)`, `(store_id, return_code)` đều scoped và partial theo `deleted_at IS NULL`. Mọi bảng giao dịch đều có `store_id` (kể cả denormalize xuống bảng item — tốt cho filter tenant).
- **CHECK constraints phong phú:** chặn giá trị âm, chặn enum rác, ràng buộc scope `user_roles`, ràng buộc kỳ hạn subscription.
- **Partial index thông minh:** `debt_balance > 0`, `status = 'PENDING'`, `deleted_at IS NULL` — giảm kích thước index đáng kể.
- **Materialized view dùng đúng:** có UNIQUE index (điều kiện bắt buộc cho `REFRESH ... CONCURRENTLY`) và có `DashboardRefreshScheduler` refresh thật.
- **`products.total_stock`** có cơ chế recompute chủ động qua native query (`ProductRepository.java:46`) thay vì để trôi.
- **Bảng tài chính immutable** (orders, payments, inventory_transactions không có deleted_at) — đúng chuẩn kế toán; V4 dùng `IF NOT EXISTS` idempotent.
- **`ddl-auto=validate` + Flyway** — đúng thực hành production, thứ tự migration V1→V6 tuyến tính, không có checksum hack.

## 5. Kết luận & trạng thái xử lý (cập nhật 2026-07-08)

Nền tảng schema tốt (7.5/10 tại thời điểm review), các nguyên tắc lớn (tiền tệ, timezone, tenant isolation, enum string) đều đúng. Rủi ro tập trung ở **độ lệch giữa 3 nguồn sự thật: migration ↔ entity ↔ enum Java**, vì `ddl-auto=validate` không đủ sức bắt các lệch này.

**Đã xử lý toàn bộ danh sách** qua 5 commit (mỗi nhóm compile pass trước khi commit):

| Commit | Nội dung |
|---|---|
| `6aed30d` | CHECK `payment_method` 6 giá trị, 7 index thiếu, partial unique inventory, CHECK `pending_plan`/`pending_cycle` |
| `84ac478` | Sync fields cho 2 bảng item, unaccent FTS (trigger + query), 4 enum mới (`ReturnOrderStatus`, `RefundMethod`, `InventoryTransactionType`, `SyncOperation`) |
| `e149baf` | `@Version` 7 entity, `@PreUpdate` 12 entity, `@SQLRestriction` 4 entity, bỏ `orphanRemoval` |
| `1f28701` | Đồng bộ `@Index` metadata với migration, `Role.description` TEXT, comment `RoleName` |
| `4559682` | Nới `chk_inv_tx_qty` thành `quantity <> 0` (bug phát hiện sau review: TRANSFER/ADJUSTMENT ghi delta có dấu, CHECK `> 0` chặn mọi lệnh chuyển kho) |

**Còn mở (theo dõi tiếp):**

1. `orders.payment_method` / `purchase_orders.payment_method` vẫn là String không CHECK — 3 mức enforcement khác nhau cho cùng khái niệm; docs API PURCHASE_ORDER.md còn ghi giá trị `TRANSFER` không thuộc enum `PaymentMethod`.
2. Convention team "migration không DROP bảng dữ liệu nghiệp vụ" — chưa ghi thành văn bản quy trình.
3. Index FK phụ (`created_by`, `last_modified_by_user`...) — giữ nguyên chờ query thực tế (đúng đề xuất gốc).
4. `chk_payments_reference` — giữ nguyên có chủ đích (payment nội bộ hợp lệ).
5. Validation nghiệp vụ cho adjust inventory (`@NotNull quantity` + chặn kết quả tồn < 0) — thuộc phạm vi review 05 (service layer), giờ đã khả thi sau khi nới `chk_inv_tx_qty`.
