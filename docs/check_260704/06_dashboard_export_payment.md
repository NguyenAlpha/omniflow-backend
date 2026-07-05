# Đánh giá Dashboard, Export, Payment, Notification, Admin — 2026-07-06

## 1. Phạm vi đã kiểm tra

- **Services:** `DashboardService`, `DashboardRefreshScheduler`, `ExportService`, `PaymentService`, `AdminStatsService`
- **Controllers:** `DashboardController`, `ExportController`, `PaymentController`, `NotificationController`, `AdminUserController`
- **Entities:** `Payment`
- **Repositories:** `PaymentRepository`, `InventoryRepository` (low-stock & export queries), `OrderRepository` (export & recent), `PurchaseOrderRepository` (export)
- **DTO:** `PaymentCreateRequest`, `DashboardResponse`, `AdminStatsResponse`

---

## 2. Tổng quan nhận xét

Dashboard và Admin ở mức tốt: Dashboard dùng materialized view được refresh định kỳ, tránh scan bảng lớn; Admin bảo vệ đúng bằng `hasRole('SUPER_ADMIN')` ở class level. Export và Notification có một số vấn đề nhỏ về chất lượng. PaymentService có **lỗ hổng cách ly tenant nghiêm trọng** lặp lại pattern đã gặp ở các module trước.

---

## 3. Vấn đề phát hiện

### [CRITICAL] IDOR trong PaymentService — get/delete/create không kiểm tra store

**Vị trí:**
- `PaymentService.get()` line 44–47: `paymentRepository.findByPublicId(publicId)` — không assert `payment.getStore().getId().equals(storeId)`
- `PaymentService.delete()` line 206–208: cùng pattern
- `PaymentService.create()` line 86–87: `customerRepository.findByPublicId(request.customerPublicId())` — unscoped
- `PaymentService.create()` line 95–96: `supplierRepository.findByPublicId(request.supplierPublicId())` — unscoped

**Mô tả:** Controller bảo vệ đúng bằng `@PreAuthorize("@storeAccess.isMember(#storeId, ...)")` và `isOwnerOrManager`, nhưng service sau đó lookup `Payment` bằng `findByPublicId` toàn cục, không đối chiếu `payment.store.id == storeId`. Tương tự, `create` có thể link một payment với `Customer`/`Supplier` của tenant khác (chỉ biết `publicId`), rồi trừ `debtBalance` của họ.

**Tác động:**
- Member store A gọi `GET /api/stores/{storeIdA}/payments/{publicIdB}` với publicId của store B → xem được bản ghi thanh toán store B.
- Owner store A gọi `DELETE /api/stores/{storeIdA}/payments/{publicIdB}` → xóa payment store B **và cộng ngược debtBalance vào customer/supplier store B** → phá dữ liệu tài chính tenant khác.
- Owner store A gọi `POST /api/stores/{storeIdA}/payments` với `customerPublicId` của store B → trừ công nợ khách hàng tenant khác.

**Đề xuất:** Sau khi `findByPublicId`, assert `payment.getStore().getId().equals(storeId)`, ném 404 nếu sai. Với customer/supplier trong `create`, dùng `findByPublicIdAndBusinessId(...)` hoặc verify sau khi fetch.

---

### [HIGH] Thiếu `@Version` trên `Customer.debtBalance` — lost update khi thanh toán đồng thời

**Vị trí:** `PaymentService.create()` và `PaymentService.delete()` — cả hai đều thực hiện read-modify-write trên `customer.debtBalance` và `supplier.debtBalance` không có optimistic lock.

**Mô tả:** Hai yêu cầu thanh toán đồng thời cho cùng một khách hàng: cả hai đọc `debtBalance = 1000`, cả hai trừ `500`, cả hai ghi `500` → kết quả cuối `500` thay vì `0`. Đã được ghi nhận ở `temp1.txt` nhưng cần xác nhận thêm tại chính `PaymentService`.

**Đề xuất:** Thêm `@Version Long version` vào `Customer` và `Supplier` entity; map `OptimisticLockingFailureException` → HTTP 409 trong `GlobalExceptionHandler`.

---

### [MEDIUM] `@NotBlank` trên `PaymentMethod` (enum) — annotation sai kiểu, null bị lưu thành chuỗi "null"

**Vị trí:** `PaymentCreateRequest.java` line 14: `@NotBlank PaymentMethod paymentMethod`

**Mô tả:** `@NotBlank` chỉ áp dụng cho `CharSequence`. Trên kiểu `enum`, bean validation spec cho phép bỏ qua constraint này (không ném lỗi startup nhưng cũng không validate). Nếu client gửi `"paymentMethod": null`, field vẫn qua `@Valid` và đến `String.valueOf(request.paymentMethod())` tại `PaymentService.create()` line 110 → chuỗi literal `"null"` được lưu vào cột `payment_method` trong DB.

**Đề xuất:** Đổi `@NotBlank` thành `@NotNull` cho field enum.

---

### [MEDIUM] Export không lọc `deletedAt IS NULL` trong truy vấn tồn kho

**Vị trí:** `InventoryRepository.findByStoreIdWithDetails()` — query không có điều kiện `i.deletedAt IS NULL`; được gọi từ `ExportService.exportInventoryExcel()` line 108.

**Mô tả:** File Excel tồn kho xuất ra bao gồm cả các bản ghi inventory đã soft-delete. Người dùng nhận dữ liệu không còn chính xác.

**Đề xuất:** Thêm `AND i.deletedAt IS NULL` vào query `findByStoreIdWithDetails`.

---

### [MEDIUM] Export không giới hạn số dòng — nguy cơ OOM với store lớn

**Vị trí:**
- `ExportService.exportOrdersExcel()` line 61: `orderRepository.findForExport(storeId, fromInstant, toInstant)` — nếu `from`/`to` là null, query từ `Instant.EPOCH` đến `Instant.now()`, load toàn bộ lịch sử đơn hàng vào RAM.
- `ExportService.exportInventoryExcel()` line 108: tương tự, load toàn bộ inventory.

**Mô tả:** Với store có hàng chục nghìn đơn hàng, một request export không có date filter sẽ load tất cả entity (cùng lazy-loaded relations) vào heap, gây OOM hoặc GC pressure nghiêm trọng. Toàn bộ dữ liệu cũng được giữ trong `byte[]` trước khi trả response.

**Đề xuất:** Đặt giới hạn mặc định cho date range (ví dụ tối đa 3 tháng nếu không có filter), hoặc dùng streaming write (POI `SXSSFWorkbook` thay `XSSFWorkbook`) để tránh giữ toàn bộ sheet trong RAM.

---

### [LOW] NotificationController dùng `.size()` để đếm — load toàn bộ dữ liệu vào RAM

**Vị trí:** `NotificationController.summary()` line 33:
```java
long lowStock = inventoryRepository.findLowStockItems(storeId).size();
```

**Mô tả:** `findLowStockItems` trả `List<Inventory>` với `JOIN FETCH product, warehouse`. Gọi `.size()` trên list này để lấy count buộc phải load toàn bộ các entity vào heap. Nếu store có nhiều mặt hàng dưới mức tồn kho tối thiểu thì đây là chi phí không cần thiết.

**Đề xuất:** Thêm method `countLowStockItems(@Param("storeId") Long storeId)` trong `InventoryRepository` dùng `SELECT COUNT(i)`.

---

### [LOW] DashboardService: `toBd()` dùng `BigDecimal.valueOf(n.doubleValue())` — mất độ chính xác

**Vị trí:** `DashboardService.toBd()` line 111–112:
```java
if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
```

**Mô tả:** Nếu DB trả về `BigDecimal` nhưng không match `instanceof BigDecimal` (ví dụ `Double` sau khi native query mapping), sẽ đi qua nhánh `Number` và convert qua `double` → mất độ chính xác với số lớn (doanh thu hàng tỷ đồng). Nhánh `instanceof BigDecimal` ở trên bắt được phần lớn trường hợp, nhưng còn `double`/`Double` thì không.

**Đề xuất:** Thêm nhánh `if (v instanceof Double d) return BigDecimal.valueOf(d)` hoặc tổng quát hơn dùng `new BigDecimal(v.toString())` để tránh mất độ chính xác.

---

## 4. Điểm tốt cần giữ nguyên

- **Dashboard dùng materialized view** (`mv_monthly_revenue`, `mv_inventory_summary`) refresh mỗi 15 phút thay vì aggregate query trực tiếp trên bảng lớn — đúng cách thiết kế cho dashboard B2B.
- **`DashboardRefreshScheduler`** dùng `REFRESH MATERIALIZED VIEW CONCURRENTLY` nên không block đọc trong lúc refresh.
- **Export PDF đúng cách cách ly tenant**: `purchaseOrderRepository.findForExport(publicId, storeId)` truyền cả `storeId` vào query — là một trong số ít chỗ trong codebase làm đúng tenant check ở tầng repository.
- **`AdminUserController`** đặt `@PreAuthorize("hasRole('SUPER_ADMIN')")` ở class level — bảo vệ toàn bộ endpoint admin một cách nhất quán, không bỏ sót endpoint nào.
- **`PaymentService.delete()`** đảo ngược `debtBalance` trước khi xóa — tránh để lại dữ liệu tài chính mất nhất quán khi xóa bản ghi thanh toán.

---

## 5. Tóm tắt ưu tiên

| # | Vấn đề | Mức |
|---|---------|-----|
| 1 | IDOR trong `PaymentService.get/delete/create` — lookup payment/customer/supplier không kiểm tra store | CRITICAL |
| 2 | Thiếu `@Version` trên `Customer`/`Supplier.debtBalance` — lost update khi thanh toán đồng thời | HIGH |
| 3 | `@NotBlank` sai kiểu trên `PaymentMethod` enum → null lưu thành chuỗi "null" | MEDIUM |
| 4 | Export tồn kho bao gồm bản ghi đã soft-delete | MEDIUM |
| 5 | Export không giới hạn số dòng — OOM với store lớn | MEDIUM |
| 6 | `NotificationController.summary()` load toàn bộ list để đếm | LOW |
| 7 | `DashboardService.toBd()` mất độ chính xác khi convert qua `double` | LOW |
