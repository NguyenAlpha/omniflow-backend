# Order Lifecycle

Mô tả vòng đời của một đơn bán hàng — từ lúc tạo đến khi hoàn thành hoặc huỷ —
bao gồm các side effect lên tồn kho, công nợ khách hàng, và ràng buộc nghiệp vụ.

---

## 1. State machine

```
              create
                │
                ▼
           [PENDING]
           /        \
     complete        cancel
          │               │
          ▼               ▼
    [COMPLETED]      [CANCELLED]
```

- `COMPLETED` và `CANCELLED` là trạng thái cuối — không thể chuyển tiếp.
- Không có chuyển trạng thái từ `CANCELLED` → `PENDING` hay `COMPLETED` → bất cứ đâu.

---

## 2. Luồng tạo đơn — `create`

```
OrderService.create(storeId, request, currentUser)
    [yêu cầu: currentUser là OWNER hoặc MANAGER của store]
    │
    ├── findStoreOrThrow(storeId)
    │
    ├── orderCode unique trong store?
    │   └── findByStoreIdAndOrderCode(storeId, request.orderCode).isPresent()
    │       └── true → throw IllegalArgumentException("Order code already exists")
    │
    ├── request.customerPublicId != null?
    │   ├── true  → findByPublicId → Customer (404 nếu không tìm thấy)
    │   └── false → customer = null  (khách vãng lai)
    │
    ├── findByPublicId(warehousePublicId) → Warehouse (404 nếu không tìm thấy)
    │
    ├── [TX BEGIN]
    │
    ├── Tạo Order shell:
    │   └── status = PENDING
    │       paidAmount = 0
    │       debtAmount = 0  (sẽ gán lại sau khi tính tổng)
    │       paymentMethod = request.paymentMethod (mặc định CASH)
    │
    ├── Với mỗi item trong request.items:
    │   │
    │   ├── findByPublicId(productPublicId) → Product (404 nếu không tìm thấy)
    │   │
    │   ├── Tính lineTotal:
    │   │   base = unitPrice * quantity
    │   │   FIXED:   lineTotal = base - discount
    │   │   PERCENT: lineTotal = base * (1 - discount / 100)
    │   │
    │   ├── Tạo OrderItem (liên kết với order, product, store)
    │   │
    │   └── deductInventory(store, product, warehouse, quantity, order, userRef)
    │       ├── findByProductIdAndWarehouseId → Inventory
    │       │   └── không tìm thấy → throw ResourceNotFoundException(INVENTORY_NOT_FOUND)
    │       ├── inv.quantity < quantity → throw IllegalArgumentException("Insufficient stock")
    │       ├── UPDATE inventory SET quantity -= quantity
    │       ├── recalculateTotalStock(product.id)  ← cập nhật tổng tồn kho trên Product
    │       └── INSERT inventory_transactions (type=OUT, note="Order: {orderCode}")
    │
    ├── Tính tổng đơn:
    │   subtotal  = sum(lineTotal) của tất cả item
    │   FIXED:   discountAmt = order.discount
    │   PERCENT: discountAmt = subtotal * order.discount / 100
    │   totalAmount = subtotal - discountAmt + tax
    │
    ├── request.paidAmount > totalAmount? → throw IllegalArgumentException("Paid amount exceeds total")
    │
    ├── customer == null AND totalAmount - paidAmount > 0?
    │   └── true → throw IllegalArgumentException("Walk-in customer orders must be fully paid")
    │
    ├── Gán lại order:
    │   subtotal       = subtotal
    │   totalAmount    = totalAmount
    │   paidAmount     = request.paidAmount (0 nếu không truyền)
    │   debtAmount     = totalAmount - paidAmount
    │   paymentMethod  = request.paymentMethod (mặc định CASH)
    │
    ├── INSERT orders + order_items
    │
    ├── [TX COMMIT]
    │
    └── return OrderResponse (kèm đủ items)

Lưu ý:
- Tồn kho bị trừ ngay lúc tạo đơn, không chờ COMPLETED.
  Lý do: giữ hàng ngay để tránh oversell khi nhiều đơn cùng lúc.
- paidAmount lấy từ request (mặc định 0). debtAmount = totalAmount - paidAmount.
- Payment record KHÔNG được tạo lúc này — được tạo khi complete() để đảm bảo
  nếu đơn bị hủy, sổ sách không có dòng tiền mồ côi.
- Khách vãng lai (customer = null) bắt buộc paidAmount = totalAmount (không được nợ).
- Nếu bất kỳ item nào không đủ tồn kho, toàn bộ transaction bị rollback — không có item nào bị trừ.
```

---

## 3. Luồng hoàn thành — `complete`

```
OrderService.complete(storeId, publicId, currentUser)
    [yêu cầu: currentUser là OWNER hoặc MANAGER của store]
    │
    ├── findStoreOrThrow(storeId)
    │
    ├── findByPublicId(publicId) → Order (404 nếu không tìm thấy)
    │
    ├── order.status == "COMPLETED"? → throw IllegalArgumentException
    ├── order.status == "CANCELLED"? → throw IllegalArgumentException
    │
    ├── [TX BEGIN]
    │
    ├── order.customer != null AND order.debtAmount > 0?
    │   └── true → customer.debtBalance += order.debtAmount
    │              UPDATE customers SET debt_balance = debt_balance + debtAmount
    │
    ├── order.status = "COMPLETED"
    ├── order.lastModifiedAt = now(), updatedAt = now()
    │
    ├── order.paidAmount > 0?
    │   └── true → INSERT payments (amount=paidAmount, method=order.paymentMethod, note="Order: {orderCode}")
    │              (ghi nhận toàn bộ số tiền đã thu tính đến lúc hoàn thành)
    │
    ├── [TX COMMIT]
    │
    └── return OrderResponse (items = rỗng — không fetch lại)

Lưu ý:
- Tồn kho KHÔNG thay đổi — đã bị trừ từ lúc tạo đơn.
- debtAmount và paidAmount trên order KHÔNG thay đổi khi complete.
  debtAmount phản ánh số tiền nợ còn lại, được quản lý bởi flow thanh toán.
- Chỉ customer.debtBalance được cộng thêm nếu đủ điều kiện.
- Nếu order không có customer (khách vãng lai), bước cộng nợ bị bỏ qua.
- Payment record được tạo tại đây thay vì lúc create() — đảm bảo hủy đơn không để lại
  dòng tiền mồ côi trong bảng payments.
```

---

## 4. Luồng ghi nhận thanh toán — `pay`

```
OrderService.pay(storeId, publicId, amount, currentUser)
    [yêu cầu: currentUser là OWNER hoặc MANAGER của store]
    │
    ├── findStoreOrThrow(storeId)
    │
    ├── findByPublicId(publicId) → Order (404 nếu không tìm thấy)
    │
    ├── order.status == "CANCELLED"? → throw IllegalArgumentException
    │
    ├── amount > order.debtAmount? → throw IllegalArgumentException("Payment exceeds remaining debt")
    │
    ├── [TX BEGIN]
    │
    ├── order.paidAmount += amount
    ├── order.debtAmount -= amount
    │
    ├── order.status == "COMPLETED" AND order.customer != null?
    │   └── true → customer.debtBalance -= amount
    │              UPDATE customers SET debt_balance = debt_balance - amount
    │
    ├── order.lastModifiedAt = now(), updatedAt = now()
    │
    ├── order.status == "COMPLETED"?
    │   ├── true  → INSERT payments (amount=amount, method=order.paymentMethod, note="Order: {orderCode}")
    │   └── false → không INSERT (đơn PENDING — payment sẽ được ghi nhận khi complete)
    │
    ├── [TX COMMIT]
    │
    └── return OrderResponse (items = rỗng)

Lưu ý:
- Đơn PENDING: chỉ cập nhật order.paidAmount và order.debtAmount.
  customer.debtBalance chưa bị chạm vì đơn chưa COMPLETED.
  Khi COMPLETED sau đó, hệ thống cộng order.debtAmount (đã giảm) vào customer.debtBalance
  và INSERT payment cho toàn bộ paidAmount tại thời điểm đó.
- Đơn COMPLETED: giảm customer.debtBalance và INSERT payment ngay lập tức.
- Không thể thanh toán đơn đã CANCELLED.
```

---

## 5. Luồng huỷ đơn — `cancel`

```
OrderService.cancel(storeId, publicId, currentUser)
    [yêu cầu: currentUser là OWNER hoặc MANAGER của store]
    │
    ├── findStoreOrThrow(storeId)
    │
    ├── findByPublicIdWithItems(publicId) → Order + OrderItems (JOIN FETCH)
    │   └── 404 nếu không tìm thấy
    │
    ├── order.status == "COMPLETED"? → throw IllegalArgumentException
    ├── order.status == "CANCELLED"? → throw IllegalArgumentException
    │
    ├── [TX BEGIN]
    │
    ├── Với mỗi item trong order.orderItems:
    │   │
    │   └── restoreInventory(store, product, warehouse, quantity, order, userRef)
    │       ├── findByProductIdAndWarehouseId → Inventory
    │       │   └── không tìm thấy → tạo mới Inventory (quantity = 0)
    │       │       (trường hợp kho đã bị xoá sau khi tạo đơn)
    │       ├── UPDATE inventory SET quantity += quantity
    │       ├── recalculateTotalStock(product.id)
    │       └── INSERT inventory_transactions (type=IN, note="Cancel order: {orderCode}")
    │
    ├── order.status = "CANCELLED"
    ├── order.lastModifiedAt = now(), updatedAt = now()
    │
    ├── [TX COMMIT]
    │
    └── return OrderResponse (kèm đủ items — đã được fetch bởi findByPublicIdWithItems)

Lưu ý:
- Tồn kho được hoàn trả đầy đủ cho tất cả items trong đơn.
- customer.debtBalance KHÔNG thay đổi — complete chưa xảy ra nên chưa có nợ nào được ghi.
- Nếu bản ghi Inventory bị xoá giữa chừng (edge case), hệ thống tự tạo lại với quantity = 0 rồi cộng vào.
  Không throw lỗi — đảm bảo cancel luôn thành công.
```

---

## 6. Tính toán tài chính

```
Với mỗi item:
    base      = unitPrice × quantity
    FIXED:    lineTotal = base − item.discount
    PERCENT:  lineTotal = base × (1 − item.discount / 100)

Tổng đơn:
    subtotal    = Σ lineTotal
    FIXED:    discountAmt = order.discount
    PERCENT:  discountAmt = subtotal × order.discount / 100
    totalAmount = subtotal − discountAmt + tax

Khởi tạo:
    paidAmount = request.paidAmount (mặc định 0)
    debtAmount = totalAmount − paidAmount
```

`discountType` áp dụng độc lập ở **hai cấp**:
- Cấp item — `items[].discount` + `items[].discountType`
- Cấp đơn hàng — `order.discount` + `order.discountType`

---

## 7. Ràng buộc nghiệp vụ

| Ràng buộc | Được kiểm tra ở đâu |
|:---|:---|
| Chỉ OWNER / MANAGER được tạo, complete, cancel | `@PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")` |
| `orderCode` phải unique trong store | `OrderService.create` — `findByStoreIdAndOrderCode` |
| Mỗi đơn phải có ít nhất 1 item | `@NotEmpty` trên `OrderCreateRequest.items` |
| Tồn kho phải đủ khi tạo đơn | `OrderService.deductInventory` — so sánh `inv.quantity` với `request.quantity` |
| Chỉ đơn `PENDING` mới được complete / cancel | Guard check ở đầu `complete` và `cancel` |
| Không thể complete / cancel một đơn đã ở trạng thái cuối | Guard check — ném `IllegalArgumentException` |
| `paidAmount` khi tạo đơn không được vượt `totalAmount` | `OrderService.create` — so sánh trước khi gán |
| Khách vãng lai bắt buộc `paidAmount == totalAmount` | `OrderService.create` — kiểm tra sau khi tính `totalAmount` |
| `amount` thanh toán không được vượt `order.debtAmount` | `OrderService.pay` — so sánh trước khi cập nhật |
| Không thể thanh toán đơn đã `CANCELLED` | Guard check ở đầu `pay` |

---

## 8. Side effects tóm tắt

| Hành động | Tồn kho | `customer.debtBalance` | `InventoryTransaction` | `Payment` |
|:---|:---|:---|:---|:---|
| `create` | Trừ ngay theo từng item | Không đổi | INSERT (type=`OUT`) × số item | Không có |
| `complete` | Không đổi | Cộng `order.debtAmount` (nếu có khách và nợ > 0) | Không có | INSERT nếu `paidAmount > 0` |
| `pay` (PENDING) | Không đổi | Không đổi | Không có | Không có |
| `pay` (COMPLETED) | Không đổi | Giảm `amount` (nếu có khách) | Không có | INSERT |
| `cancel` | Hoàn trả toàn bộ | Không đổi | INSERT (type=`IN`) × số item | Không có |
