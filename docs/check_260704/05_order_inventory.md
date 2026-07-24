# Đánh giá Order & Inventory — 2026-07-04

## 1. Phạm vi đã kiểm tra

Đã đọc toàn bộ các file sau:

- **Services:** `OrderService.java`, `PurchaseOrderService.java`, `ReturnOrderService.java`, `InventoryService.java`, `WarehouseService.java`
- **Controllers:** `OrderController`, `PurchaseOrderController`, `ReturnOrderController`, `InventoryController`, `WarehouseController`
- **Entities:** `Order`, `OrderItem`, `PurchaseOrder`, `PurchaseOrderItem`, `ReturnOrder`, `ReturnOrderItem`, `Inventory`, `InventoryTransaction`, `Warehouse`
- **Repositories:** `OrderRepository`, `OrderItemRepository`, `PurchaseOrderRepository`, `ReturnOrderRepository`, `ReturnOrderItemRepository`, `InventoryRepository`, `InventoryTransactionRepository`, `WarehouseRepository`, `ProductRepository` (recalculateTotalStock)
- **DTO request:** `order\*` (OrderCreateRequest, OrderItemRequest, OrderPayRequest, ReturnOrderCreateRequest, ReturnOrderItemRequest), `purchase\*`, `inventory\*`, `warehouse\*`
- **Tham khảo:** `docs\ORDER_LIFECYCLE.md`, `security\StoreAccessEvaluator.java`, `exception\GlobalExceptionHandler.java`, `service\AuditService.java`

## 2. Tổng quan nhận xét

Kiến trúc tổng thể sạch: tiền dùng `BigDecimal` xuyên suốt, transaction bao trọn flow (tạo đơn + trừ kho + ghi `InventoryTransaction` trong cùng 1 `@Transactional`), mọi biến động kho đều được log kèm `previousQuantity`, state machine đơn hàng khớp với tài liệu `ORDER_LIFECYCLE.md`, và không có external call (email/HTTP) bên trong transaction.

Tuy nhiên có **2 lỗ hổng nghiêm trọng mang tính hệ thống**:

1. **Tenant isolation bị phá vỡ ở tầng service**: `@PreAuthorize` chỉ xác nhận user thuộc `storeId` trên URL, nhưng service tra cứu mọi entity bằng `findByPublicId(...)` **toàn cục, không đối chiếu store** → user của store A có thể đọc/complete/cancel/pay đơn của store B, trừ kho store B, xóa warehouse store B.
2. **ReturnOrder gần như không có validation** — không giới hạn số lượng trả, không kiểm tra sản phẩm có trong đơn gốc, giá hoàn tiền do client tự quyết → có thể bơm tồn kho ảo và xóa công nợ tùy ý.

Với hệ thống tài chính, cả hai phải xử lý trước khi đưa vào production.

Về chống oversell: không dùng pessimistic lock hay atomic UPDATE, nhưng `Inventory` có `@Version syncVersion` nên 2 đơn đồng thời **không oversell được** (transaction sau fail khi flush). Điểm yếu là failure này trả về HTTP 500 và không có retry (xem mục 3).

## 3. Vấn đề phát hiện

### [CRITICAL] IDOR xuyên tenant — mọi lookup theo publicId không filter store_id

- **Vị trí:**
  - `OrderService.java:63, 119, 159, 206` (`findByPublicIdWithItems` / `findByPublicIdWithCustomer` không có điều kiện store), `OrderService.java:283-294` (`resolveCustomer`, `resolveWarehouse`), `OrderService.java:324` (product)
  - `PurchaseOrderService.java:58, 68, 71, 95, 133, 185, 204`
  - `ReturnOrderService.java:47, 60, 63, 87, 115, 163`
  - `InventoryService.java:61, 64, 112-119` (adjust/transfer)
  - `WarehouseService.java:101-104` (`findWarehouseOrThrow` — dùng cho get/update/**delete**)
  - Repository: `OrderRepository.findByPublicIdWithItems`, `PurchaseOrderRepository.findByPublicIdWithItems`, `ReturnOrderRepository.findByPublicIdWithItems`, `InventoryRepository.findByProductIdAndWarehouseId` — đều không có tham số storeId
- **Mô tả:** `@PreAuthorize("@storeAccess.isMember(#storeId, ...)")` chỉ chứng minh user thuộc store trên URL path. Service sau đó gọi `findStoreOrThrow(storeId)` (chỉ check store tồn tại) rồi load Order/Customer/Warehouse/Product/Supplier/ReturnOrder theo `publicId` **toàn cục**, không hề so `entity.store.id == storeId`. publicId là UUID khó đoán nhưng bị lộ qua response, log, export, sync local-first (thiết bị offline giữ toàn bộ UUID).
- **Tác động:** Bất kỳ user nào là member của *một* store bất kỳ có thể: đọc đơn hàng/đơn nhập/đơn trả của tenant khác; **complete/cancel/pay đơn của tenant khác** (làm sai lệch công nợ, tồn kho của họ); tạo đơn bán trừ kho của tenant khác (`warehousePublicId` + `productPublicId` của store khác); điều chỉnh/chuyển kho tenant khác; **xóa warehouse của tenant khác**. Đây là vi phạm cách ly dữ liệu nghiêm trọng nhất có thể với B2B SaaS.
- **Đề xuất:** Thêm điều kiện store vào mọi query lookup: `findByPublicIdAndStoreId(publicId, storeId)` (hoặc sau khi load, assert `entity.getStore().getId().equals(storeId)` và ném 404). Với Product/Customer/Supplier thuộc scope business thì đối chiếu theo businessId của store. Viết integration test: user store A gọi API với publicId của store B phải nhận 404.

> **Trạng thái (2026-07-11): Đã xử lý** — commit `ef76630`. Mọi lookup theo publicId đã scoped
> theo tenant ở tầng query: `OrderRepository`/`PurchaseOrderRepository`/`ReturnOrderRepository`
> thêm điều kiện `store.id` vào `findByPublicIdWithItems`/`findByPublicIdWithCustomer` và đổi
> `findByPublicId` → `findByPublicIdAndStoreId`; `WarehouseRepository`/`PaymentRepository` tương tự.
> Product/Customer/Supplier (scope business) dùng `findByBusinessIdAndPublicId` sẵn có, đối chiếu
> `store.getBusiness().getId()`. Đã sửa cả `InventoryService.listByWarehouse` (đọc tồn kho theo
> warehousePublicId của tenant khác — cùng lớp lỗi, không được liệt kê ở trên).
> `InventoryRepository.findByProductIdAndWarehouseId` giữ nguyên: an toàn vì product + warehouse
> đầu vào đã được scoped. Integration test cross-tenant chưa viết (test suite hiện không compile
> từ trước — xem backlog).

### [CRITICAL] ReturnOrder không có bất kỳ validation nghiệp vụ nào — thổi phồng tồn kho & xóa công nợ tùy ý

- **Vị trí:** `ReturnOrderService.java:52-110` (create), `112-158` (complete); DTO `dto\request\order\ReturnOrderCreateRequest.java` và `ReturnOrderItemRequest.java` (không có annotation validation nào)
- **Mô tả:** Khi tạo và hoàn tất đơn trả:
  1. **Không kiểm tra sản phẩm trả có nằm trong đơn gốc** — trả sản phẩm bất kỳ.
  2. **Không kiểm tra số lượng trả ≤ số lượng đã mua**, và **không cộng dồn qua nhiều đơn trả** (tạo N đơn trả cho cùng 1 đơn gốc, mỗi đơn trả full số lượng → kho tăng vô hạn).
  3. **`unitPrice` hoàn tiền do client gửi lên**, không đối chiếu giá đã bán trong `OrderItem` → refund tùy ý, dòng `customer.debtBalance -= totalRefund` (line 139) cho phép xóa công nợ khách hàng bất kỳ số tiền nào.
  4. DTO không có `@NotNull/@DecimalMin`: `quantity` **âm** đi qua `restoreInventory` sẽ *trừ* kho không qua kiểm tra đủ hàng; `quantity`/`items`/`returnCode` null gây NPE 500.
  5. **Không kiểm tra trạng thái đơn gốc**: trả hàng cho đơn PENDING hoặc CANCELLED được chấp nhận — đơn bị cancel đã hoàn kho, trả tiếp lần nữa → kho nhân đôi.
  6. Kho hoàn trả là `warehousePublicId` client chọn tự do, không bắt buộc là kho của đơn gốc (kết hợp với IDOR ở trên có thể bơm kho store khác).
- **Tác động:** Gian lận tài chính trực tiếp: tự tạo tồn kho ảo, tự xóa công nợ, refund âm/dương tùy ý. Đây là lỗ hổng nặng nhất về tính đúng đắn dữ liệu tài chính.
- **Đề xuất:** (a) Thêm validation DTO (`@NotNull`, `@NotBlank returnCode`, `@NotEmpty items`, `@DecimalMin("0.01") quantity`, `@DecimalMin("0.00") unitPrice`); (b) chỉ cho trả đơn `COMPLETED`; (c) mỗi item phải khớp `OrderItem` của đơn gốc, `unitPrice` lấy từ `OrderItem` (không nhận từ client); (d) validate `quantity + tổng đã trả trước đó (các ReturnOrder không CANCELLED) ≤ quantity đã mua` — dùng `findByOriginalOrderId` sẵn có; (e) mặc định hoàn về `originalOrder.getWarehouse()`.

> **Trạng thái (2026-07-16): Đã xử lý** — commit `80846ef`. Đủ (a)–(e): DTO có validation
> (`warehousePublicId` và `unitPrice` bị **xóa khỏi DTO** — web UI đơn trả chưa build nên không
> breaking); chỉ trả đơn COMPLETED; item phải thuộc đơn gốc (lấy Product từ chính OrderItem,
> khỏi lookup lại); số lượng cộng dồn qua các đơn trả không CANCELLED ≤ số đã mua; kho hoàn cố
> định theo `originalOrder.getWarehouse()`. Lưu ý quyết định: đơn giá hoàn = **giá hiệu dụng sau
> chiết khấu dòng** (`SUM(totalPrice)/SUM(quantity)` theo product, HALF_UP scale 2) chứ không
> phải `unitPrice` gốc — tránh hoàn nhiều hơn số khách thực trả với item có giảm giá. Chiết khấu
> cấp đơn (order-level discount) chưa được phân bổ vào giá hoàn — chấp nhận ở giai đoạn này.

### [HIGH] Race condition tồn kho: chỉ dựa vào optimistic lock, không retry, lỗi trả về 500

- **Vị trí:** `OrderService.java:230-257` (deductInventory — pattern read-check-write), `InventoryService.java:69-98, 123-153`, `PurchaseOrderService.java:246-266`; `Inventory.java:47-50` (`@Version syncVersion`); `GlobalExceptionHandler.java` (không có handler cho `OptimisticLockingFailureException`); toàn codebase không có `@Lock(PESSIMISTIC_WRITE)` hay atomic `UPDATE ... SET quantity = quantity - :q WHERE quantity >= :q`
- **Mô tả:** Trừ/cộng kho theo mẫu đọc-kiểm-ghi. Nhờ `@Version` trên `Inventory`, 2 đơn đồng thời bán cùng sản phẩm **không gây oversell** (transaction thứ hai fail khi flush và rollback toàn bộ, `previousQuantity` trong `InventoryTransaction` do đó cũng nhất quán). Nhưng: (1) `ObjectOptimisticLockingFailureException` không được `GlobalExceptionHandler` xử lý → khách nhận HTTP 500 vô nghĩa, không có cơ chế retry — POS giờ cao điểm bán hàng chạy nhiều máy sẽ fail đơn thường xuyên; (2) `syncVersion` kiêm nhiệm vai trò version cho sync local-first — nếu flow sync sau này set field này thủ công, lớp bảo vệ chống oversell sẽ vô hiệu một cách thầm lặng.
- **Tác động:** Đơn hàng hợp lệ bị fail 500 ngẫu nhiên dưới tải đồng thời; rủi ro tiềm ẩn oversell nếu sync flow đụng vào `syncVersion`.
- **Đề xuất:** Chuyển sang pessimistic lock cho dòng inventory (`@Lock(LockModeType.PESSIMISTIC_WRITE)` trên `findByProductIdAndWarehouseId`) hoặc atomic UPDATE có điều kiện `quantity >= :q` (check số dòng ảnh hưởng). Tối thiểu: bắt `OptimisticLockingFailureException` trong handler trả về 409 kèm thông báo "thử lại", và tách version sync khỏi version lock.

> **Trạng thái (2026-07-16): Đã xử lý một phần** — commit `046b5a3`. `GlobalExceptionHandler`
> bắt `OptimisticLockingFailureException` → 409 + `ErrorCode.CONCURRENT_MODIFICATION` kèm thông
> báo thử lại (mức "tối thiểu" trong đề xuất). Pessimistic lock/atomic UPDATE và việc tách
> version sync khỏi version lock: **hoãn** — đợi Phase 2 (mobile sync) chốt thiết kế sync rồi
> quyết một thể, tránh sửa hai lần.

### [HIGH] Customer/Supplier debtBalance: read-modify-write không có @Version — lost update công nợ

- **Vị trí:** `OrderService.java:125-129` (complete), `172-176` (pay); `PurchaseOrderService.java:152-156, 217-221`; `ReturnOrderService.java:139-141`; `Customer.java` / `Supplier.java` — **không có `@Version`** (grep toàn entity chỉ thấy @Version ở Payment, ReturnOrder, PurchaseOrder, Inventory, Product, OrderItem, Order)
- **Mô tả:** `debtBalance` được cập nhật kiểu `customer.setDebtBalance(customer.getDebtBalance().add(x))` trong Java. Hai thao tác đồng thời trên cùng khách hàng (vd: complete 2 đơn nợ, hoặc pay + complete, hoặc complete + return) đọc cùng giá trị cũ → một bên ghi đè bên kia, mất tiền nợ mà không có dấu vết.
- **Tác động:** Sai lệch công nợ khách hàng/nhà cung cấp — lỗi tài chính khó truy vết vì không có exception nào xảy ra.
- **Đề xuất:** Dùng atomic UPDATE: `UPDATE customers SET debt_balance = debt_balance + :delta WHERE id = :id` (`@Modifying`), hoặc thêm `@Version` cho Customer/Supplier. Ưu tiên atomic UPDATE vì tránh luôn vấn đề retry.

> **Trạng thái (2026-07-16): Đã xử lý** — chọn phương án `@Version`: Customer/Supplier đã có
> `@Version syncVersion` từ commit `e149baf` (đợt sửa entity trước đó), và commit `046b5a3` bổ
> sung handler trả 409 `CONCURRENT_MODIFICATION` thay vì 500. Không dùng atomic UPDATE để giữ
> nhất quán pattern optimistic lock của toàn codebase.

### [HIGH] Discount không có cận trên — tổng tiền âm

- **Vị trí:** `OrderService.java:363-377` (`computeLineTotal`, `computeDiscount`); DTO `OrderCreateRequest.java` / `OrderItemRequest.java` (chỉ có `@DecimalMin("0.00")`, không có max)
- **Mô tả:** Discount PERCENT có thể > 100 (vd 500%), discount FIXED có thể lớn hơn `unitPrice × quantity` → `lineTotal` âm; discount cấp đơn cũng vậy → `totalAmount` âm. Không có bất kỳ check `lineTotal ≥ 0` / `totalAmount ≥ 0` nào. Với `totalAmount` âm và `paidAmount = 0`, check "Paid amount cannot exceed total" (line 95) fail ngay cả đơn hợp lệ, còn `debtAmount` âm sẽ chảy vào `customer.debtBalance` khi complete... tùy tổ hợp, dữ liệu doanh thu/công nợ âm lọt vào báo cáo (`calculateRevenueByDateRange` SUM totalAmount).
- **Tác động:** Doanh thu âm, công nợ âm, báo cáo tài chính sai.
- **Đề xuất:** Validate `discountType` PERCENT → `0 ≤ discount ≤ 100`; sau khi tính, assert `lineTotal ≥ 0` và `totalAmount ≥ 0` (ném `IllegalArgumentException`). Đồng thời validate `discountType` bằng enum thay vì `DiscountType.valueOf(String)` tự do (chuỗi lạ hiện ném IllegalArgumentException 400 — chấp nhận được nhưng nên dùng `@Pattern` hoặc enum trong DTO).

> **Trạng thái (2026-07-24): Đã xử lý** — commit `d11013f`. `computeLineTotal`/`computeDiscount`
> validate PERCENT ≤ 100 (`validatePercent`); assert `lineTotal ≥ 0` và `totalAmount ≥ 0`, vượt
> thì ném `IllegalArgumentException` (400). `discountType` vẫn qua `DiscountType.valueOf` (chuỗi
> lạ → 400) — chưa đổi sang enum trong DTO, chấp nhận ở giai đoạn này.

### [HIGH] Điều chỉnh kho thủ công: DTO không validate, cho phép kho âm

- **Vị trí:** `InventoryService.java:56-101` (adjust); `dto\request\inventory\InventoryAdjustRequest.java` (không có annotation nào)
- **Mô tả:** `productPublicId`/`warehousePublicId`/`quantity` đều có thể null → NPE 500 tại `previousQuantity.add(request.quantity())`. `quantity` là delta có thể âm và **không kiểm tra kết quả ≥ 0** → tồn kho âm không giới hạn (khác với transfer đã có check đủ hàng). Điểm cộng: có `@Auditable` + ghi `InventoryTransaction type=ADJUSTMENT`, nên audit trail đầy đủ.
- **Tác động:** Tồn kho âm phá vỡ invariant của toàn hệ thống (deduct/transfer đều giả định quantity ≥ 0), `total_stock` của Product âm.
- **Đề xuất:** Thêm `@NotNull` cho 3 field; trong service, nếu `previousQuantity.add(quantity) < 0` thì ném `IllegalArgumentException` (hoặc yêu cầu lý do đặc biệt nếu nghiệp vụ thực sự cần kho âm).

> **Trạng thái (2026-07-24): Đã xử lý** — commit `83eed8b`. `InventoryAdjustRequest` thêm
> `@NotNull` cho `productPublicId`/`warehousePublicId`/`quantity`; `adjust()` chặn khi
> `previousQuantity + quantity < 0` (400). Không mở đường kho âm — nghiệp vụ hiện không cần.

### [HIGH] Hoàn tiền đơn trả không được ghi nhận vào Payment — sổ tiền lệch với thực tế

- **Vị trí:** `ReturnOrderService.java:133-150` (complete)
- **Mô tả:** Khi complete đơn trả: chỉ giảm `customer.debtBalance` (cap về 0 bằng `.max(ZERO)`) và giảm `originalOrder.debtAmount`. Không có bản ghi `Payment` (tiền hoàn ra) dù DTO có `refundMethod`; `originalOrder.paidAmount`/`totalAmount` giữ nguyên → báo cáo doanh thu (SUM totalAmount đơn COMPLETED) không trừ hàng trả; phần refund vượt quá công nợ (đáng lẽ phải trả tiền mặt lại cho khách) biến mất khỏi sổ sách; `customer.debtBalance` giảm full refund trong khi `order.debtAmount` chỉ giảm `min(refund, debt)` → hai sổ nợ lệch nhau.
- **Tác động:** Sổ quỹ và báo cáo doanh thu sai sau mỗi đơn trả; không đối soát được tiền hoàn.
- **Đề xuất:** Ghi bản ghi Payment âm (hoặc bảng refund riêng) theo `refundMethod`; chỉ giảm `customer.debtBalance` đúng bằng phần áp vào công nợ, phần còn lại ghi nhận là tiền hoàn ra; cân nhắc trường `refundedAmount` trên Order để báo cáo trừ đúng.

> **Trạng thái (2026-07-16): Đã xử lý** — commit `80846ef`. `complete()`: `appliedToDebt =
> min(refund, order.debtAmount)` trừ đồng thời vào `order.debtAmount` và `customer.debtBalance`
> (hai sổ nợ hết lệch); phần còn lại (`cashRefund`) ghi **Payment âm** theo `refundMethod`, note
> `"Return: {returnCode}"` — vì `supplier IS NULL` nên rơi vào nhóm INCOME của sổ quỹ, `sumIncome`
> tự trừ khoản hoàn, không cần đổi schema. Chưa làm: trường `refundedAmount` trên Order (báo cáo
> doanh thu vẫn chưa trừ hàng trả — cần quyết định nghiệp vụ riêng). Lưu ý edge case:
> `PaymentService.delete()` nếu xóa một Payment hoàn tiền (amount âm, có customer) sẽ đảo nợ sai
> vì khoản hoàn không đụng công nợ lúc tạo — cân nhắc chặn xóa Payment có amount < 0.

> **Cập nhật (2026-07-24):** hai mục "chưa làm" đã hoàn tất. Chặn xóa Payment `amount < 0` —
> commit `bc1a30f` (`PaymentService.delete` ném 400, hướng dẫn hủy đơn trả liên quan thay thế).
> Trường `Order.refundedAmount` — commit `0d10421`: cộng dồn khi đơn trả COMPLETED,
> `mv_monthly_revenue` (migration V11) tính `SUM(total_amount - refunded_amount)` nên doanh thu
> thuần đã trừ đúng phần hàng trả.

### [MEDIUM] Hủy đơn PENDING đã thu tiền một phần — tiền đã thu biến mất khỏi sổ

- **Vị trí:** `OrderService.java:202-228` (cancel); thiết kế ghi ở `ORDER_LIFECYCLE.md` mục 5
- **Mô tả:** Đơn PENDING có thể có `paidAmount > 0` (từ create hoặc pay). Khi cancel, kho được hoàn nhưng số tiền đã thu không được xử lý: không có Payment nào từng được ghi (đúng thiết kế "không có dòng tiền mồ côi"), nhưng cũng **không có bản ghi hoàn tiền** — tiền mặt thực tế đã thu tại quầy không còn dấu vết nào trong hệ thống.
- **Tác động:** Chênh lệch quỹ tiền mặt thực tế vs sổ sách; không đối soát được ca bán hàng.
- **Đề xuất:** Khi cancel đơn có `paidAmount > 0`: hoặc chặn và yêu cầu hoàn tiền trước, hoặc ghi cặp bút toán thu/hoàn để audit.

> **Trạng thái (2026-07-16): Đã xử lý** — commit `80846ef`, chọn phương án **chặn**: `cancel()`
> từ chối đơn có `paidAmount > 0` (400, "Use a return order to refund instead"). Hệ quả flow:
> đơn PENDING đã thu tiền phải **complete trước** (Payment được ghi) rồi hoàn qua đơn trả hàng —
> đảm bảo mọi dòng tiền đều có bút toán.

### [MEDIUM] Làm tròn: tính toán scale 10 nhưng cột numeric(15,2) — DB tự làm tròn thầm lặng

- **Vị trí:** `OrderService.java:87-92, 363-377`; các cột `precision = 15, scale = 2` trong `Order.java`, `OrderItem.java`
- **Mô tả:** `computeLineTotal` PERCENT trả về BigDecimal scale cao (divide scale 10 rồi multiply); `subtotal` được cộng từ các giá trị **chưa làm tròn** rồi cả line total lẫn subtotal bị PostgreSQL round về 2 chữ số khi lưu. Hệ quả: `subtotal` lưu trong DB có thể lệch vài xu so với tổng các `totalPrice` đã lưu của items (round-then-sum ≠ sum-then-round); `debtAmount`/`paidAmount` so sánh trên giá trị chưa làm tròn.
- **Tác động:** Lệch lẻ vài xu giữa tổng đơn và tổng item — với hệ thống tài chính sẽ gây lệch đối soát tích lũy.
- **Đề xuất:** Chuẩn hóa `setScale(2, RoundingMode.HALF_UP)` ngay sau khi tính từng `lineTotal`, rồi mới cộng subtotal; áp dụng tương tự cho `discountAmt` và `totalAmount`.

> **Trạng thái (2026-07-16): Đã xử lý** — commit `80846ef`. `computeLineTotal`,
> `computeDiscount` và `totalAmount` trong `OrderService` đều `setScale(2, HALF_UP)` ngay khi
> tính; subtotal cộng từ các lineTotal đã làm tròn nên khớp tổng items lưu DB. Đơn giá hoàn
> trong `ReturnOrderService` cũng dùng cùng quy tắc.

### [MEDIUM] Danh sách không phân trang + N+1 query

- **Vị trí:**
  - `InventoryService.java:50-54` — `listTransactions` load **toàn bộ** `inventory_transactions` của store (bảng này tăng theo từng dòng đơn hàng, sẽ phình rất nhanh) dù `InventoryTransactionRepository` đã có sẵn overload `Page<...>` không được dùng; mỗi tx còn N+1 tới product, warehouse, order, purchaseOrder, createdBy trong `toTxResponse`
  - `ReturnOrderService.java:38-42` — `list` không phân trang
  - `InventoryService.java:33-47` — `list`/`listByWarehouse` không phân trang, không JOIN FETCH product/warehouse (repo đã có `findByStoreIdWithDetails` với JOIN FETCH nhưng chỉ dùng cho export)
  - `OrderService.java:54-57` + `OrderRepository.search` — không fetch join customer/warehouse/store; `toResponse` chạm cả 3 quan hệ → tối đa ~60 query phụ mỗi trang 20 đơn
- **Tác động:** Suy giảm hiệu năng tăng dần theo dữ liệu; endpoint transactions có nguy cơ OOM/timeout với store hoạt động lâu.
- **Đề xuất:** Thêm `Pageable` cho transactions/returns/inventory (giống Order/PO); dùng JOIN FETCH hoặc `@EntityGraph` cho các quan hệ được map trong response.

> **Trạng thái (2026-07-16): Hoãn có chủ đích** — đổi response list → page là breaking change
> với web admin (các màn transactions/returns/inventory đang parse mảng phẳng). Sẽ xử lý thành
> một đợt riêng phối hợp cùng repo web; chưa lên lịch.

### [MEDIUM] Xóa warehouse không kiểm tra tồn kho còn lại; isActive không được enforce

- **Vị trí:** `WarehouseService.java:88-94` (delete); `ProductRepository.java:46` (recalculateTotalStock chỉ check `inventory.deleted_at`, không check warehouse)
- **Mô tả:** Soft-delete warehouse không kiểm tra còn hàng: các dòng `Inventory` của kho đã xóa vẫn được `recalculateTotalStock` cộng vào `products.total_stock`, nhưng không hiển thị/không bán được → tồn kho "ma". Ngoài ra `isActive = false` không được kiểm tra ở bất kỳ flow nào (tạo đơn, nhập hàng, transfer vẫn dùng kho inactive bình thường).
- **Tác động:** `total_stock` sai lệch vĩnh viễn sau khi xóa kho còn hàng; cờ isActive vô nghĩa.
- **Đề xuất:** Chặn xóa khi `SUM(quantity) > 0` trong kho (yêu cầu transfer hết trước); check `isActive` (và `deletedAt`) khi resolve warehouse trong các flow ghi.

> **Trạng thái (2026-07-24): Đã xử lý** — commit `83eed8b` (kho) + `d11013f` (order/PO).
> `WarehouseService.delete` chặn khi `SUM(quantity) > 0` (`InventoryRepository.sumQuantityByWarehouseId`).
> Enforce `isActive`: chặn tạo đơn bán/nhập hàng và chuyển hàng **vào** kho inactive (chuyển RA
> vẫn cho để rút hàng trước khi xóa). `deletedAt` đã tự loại nhờ `@SQLRestriction("deleted_at IS NULL")`
> trên entity Warehouse nên resolve không bao giờ lấy được kho đã xóa — không cần check tay.

### [LOW] Mã đơn sinh ngẫu nhiên 6 ký tự, không có unique constraint

- **Vị trí:** `OrderService.java:300`, `PurchaseOrderService.java:81`; cột `orderCode` trong `Order.java:33-34` / `PurchaseOrder.java` không `unique`
- **Mô tả:** `UUID.randomUUID().substring(0,6)` cho không gian ~16.7M giá trị; theo birthday paradox, xác suất trùng đáng kể sau vài nghìn đơn, và DB không có constraint nào chặn (check `findByStoreIdAndOrderCode` mà `ORDER_LIFECYCLE.md` mô tả không tồn tại trong code create). Hai đơn trùng mã gây nhầm lẫn chứng từ (note của Payment/InventoryTransaction tham chiếu theo orderCode).
- **Đề xuất:** Unique constraint `(store_id, order_code)` + retry khi trùng, hoặc sequence theo store.

> **Trạng thái (2026-07-24): Đã xử lý** — commit `d11013f`. Unique constraint
> `(store_id, order_code)` **đã tồn tại sẵn** trong V1 (`ux_orders_store_code`,
> `ux_po_store_code`) — nhận định "DB không có constraint" ở trên là sai, chỉ tầng ứng dụng thiếu
> check. Thêm `generateUniqueOrderCode` (Order + PurchaseOrder) tra DB trước khi dùng (tối đa 5
> lần), constraint DB làm backstop cho race TOCTOU. Không cần migration.

### [LOW] `OrderRepository.search` so sánh status (String) với cột enum

- **Vị trí:** `OrderRepository.java` — `search(...)` nhận `@Param("status") String status` so với `o.status` kiểu `OrderStatus`; `OrderService.list:51` truyền String thô từ query param
- **Mô tả:** Dựa vào coercion của Hibernate 6; status không hợp lệ (vd "FOO") không bị reject sớm mà lặng lẽ trả kết quả rỗng hoặc lỗi runtime tùy phiên bản. `findByStoreAndStatus`/`countByStoreIdAndStatus` cũng nhận String tương tự.
- **Đề xuất:** Đổi tham số sang `OrderStatus` (PurchaseOrderController đã làm đúng với `PurchaseOrderStatus`).

> **Trạng thái (2026-07-24): Đã xử lý** — commit `d11013f`. `OrderRepository.search` +
> `OrderController.list` + `OrderService.list` đổi param `String` → `OrderStatus`; Spring
> auto-convert query param, giá trị lạ → 400 (mẫu của `PurchaseOrderController`).
> `findByStoreAndStatus`/`countByStoreIdAndStatus` (String) giữ nguyên vì không có caller
> (dead code — không sửa theo nguyên tắc surgical).

### [LOW] Payment cho đơn PENDING bị dồn thành 1 bản ghi khi complete

- **Vị trí:** `OrderService.java:139-150` (complete), `185-197` (pay)
- **Mô tả:** Nhiều lần `pay` khi đơn còn PENDING không tạo Payment riêng; đến khi complete, toàn bộ `paidAmount` được ghi thành **một** Payment với `paymentMethod` hiện tại của đơn — mất chi tiết từng lần thu (thời điểm, phương thức nếu khác nhau). Đây là thiết kế có chủ đích (theo `ORDER_LIFECYCLE.md`) và nhất quán về tổng tiền, nhưng giảm độ mịn của lịch sử thu tiền.
- **Đề xuất:** Chấp nhận được ở giai đoạn hiện tại; nếu cần đối soát theo ca/phương thức, ghi Payment ngay từng lần pay kèm cờ trạng thái, void khi cancel.

## 4. Điểm tốt

- **BigDecimal toàn bộ** cho tiền và số lượng, không có `double/float`; cột `numeric(15,2)` nhất quán.
- **`@Version` (optimistic lock)** trên `Inventory`, `Order`, `PurchaseOrder`, `ReturnOrder`, `Product`, `OrderItem` — chặn được lost update và oversell tồn kho ở mức dữ liệu (dù xử lý lỗi chưa tốt — xem HIGH #3); `previousQuantity` trong log kho do đó chính xác dưới concurrency.
- **Transaction boundary đúng**: `@Transactional` bao trọn create-order + trừ kho + ghi InventoryTransaction; nếu 1 item thiếu hàng, toàn bộ rollback. `readOnly = true` dùng đúng cho mọi query. Không có external call (email/HTTP/Redis) trong transaction ghi; audit log chạy `@Async + REQUIRES_NEW` — thiết kế tốt.
- **InventoryTransaction ghi đủ mọi biến động**: bán (OUT), hủy đơn (IN), nhận PO (IN), trả hàng (IN), adjust (ADJUSTMENT), transfer (cặp bút toán ±), luôn kèm `previousQuantity`, tham chiếu order/PO và người thực hiện.
- **State machine rõ ràng và được enforce**: Order/PO/ReturnOrder chỉ chuyển từ PENDING; complete/cancel/pay trên trạng thái cuối đều bị chặn; hủy đơn hoàn kho đầy đủ; tài liệu `ORDER_LIFECYCLE.md` khớp implementation.
- **`recalculateTotalStock` là atomic UPDATE native** với subquery — không race trên `total_stock`.
- Ràng buộc tiền hợp lý ở happy path: `paidAmount ≤ totalAmount`, `pay ≤ debtAmount`, khách vãng lai bắt buộc trả đủ.
- Order/PO list có **pagination + filter + `@Max(100)`**; các query lấy chi tiết dùng **JOIN FETCH** items + product; PO items có `@BatchSize`.
- Phân quyền endpooint nhất quán: đọc = `isMember`, ghi = `isOwnerOrManager`.

## 5. Kết luận & ưu tiên xử lý

Tổng cộng **14 vấn đề**: 2 CRITICAL, 5 HIGH, 4 MEDIUM, 3 LOW.

Thứ tự xử lý đề xuất:

1. **[CRITICAL] Vá IDOR xuyên tenant** — thêm filter storeId vào mọi lookup theo publicId trong 5 service. Đây là việc cơ học (đổi query + test) nhưng ảnh hưởng rộng, làm ngay.
2. **[CRITICAL] Siết ReturnOrder** — validation DTO + chỉ trả đơn COMPLETED + item khớp đơn gốc + giá lấy từ OrderItem + chặn trả vượt số lượng cộng dồn.
3. **[HIGH] Công nợ atomic** — chuyển cập nhật `debtBalance` (Customer/Supplier) sang atomic UPDATE.
4. **[HIGH] Xử lý concurrency tồn kho** — handler cho OptimisticLockingFailureException (409 + retry) hoặc chuyển sang pessimistic lock/atomic UPDATE.
5. **[HIGH] Chặn tổng tiền âm** (cận trên discount) và **kho âm** (validate adjust).
6. **[HIGH] Ghi nhận tiền hoàn của đơn trả** vào sổ Payment.
7. Các mục MEDIUM (hủy đơn đã thu tiền, làm tròn, pagination/N+1, xóa warehouse) xử lý trong sprint kế tiếp; LOW xử lý khi tiện.

Nền tảng transaction/logging/state machine của module này tốt; rủi ro tập trung ở **kiểm soát đầu vào (ReturnOrder, adjust, discount)** và **cách ly tenant** — cả hai đều là điều kiện tiên quyết cho hệ thống tài chính multi-tenant.
