# API Reference — Inventory

Xem, điều chỉnh và chuyển tồn kho sản phẩm giữa các kho hàng. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

| Method | Endpoint | Quyền | Mô tả |
|:-------|:---------|:------|:------|
| GET | `/api/stores/{storeId}/inventory` | Thành viên store | Danh sách tồn kho (lọc theo kho) |
| GET | `/api/stores/{storeId}/inventory/transactions` | Thành viên store | Lịch sử giao dịch tồn kho |
| POST | `/api/stores/{storeId}/inventory/adjust` | OWNER / MANAGER | Điều chỉnh 1 sản phẩm |
| POST | `/api/stores/{storeId}/inventory/adjust/bulk` | OWNER / MANAGER | Điều chỉnh nhiều sản phẩm trong 1 kho |
| POST | `/api/stores/{storeId}/inventory/transfer` | OWNER / MANAGER | Chuyển 1 sản phẩm giữa 2 kho |
| POST | `/api/stores/{storeId}/inventory/transfer/bulk` | OWNER / MANAGER | Chuyển nhiều sản phẩm giữa 2 kho |

Hàng mới nhập từ nhà cung cấp nên đi qua Purchase Order (ghi nhận nhà cung cấp, giá vốn) thay vì `adjust`.

---

## Response envelope

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

---

## Lỗi chung

Áp dụng cho mọi endpoint bên dưới, không lặp lại trong từng bảng lỗi.

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT không hợp lệ / hết hạn |
| 403 | `FORBIDDEN` | Không đủ quyền với store (xem cột Quyền ở bảng tổng quan) |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## Đối tượng `InventoryResponse`

Tồn kho của một sản phẩm tại một kho cụ thể.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "storeId": 1,
  "productPublicId": "b2c3d4e5-...",
  "productName": "Cà phê sữa",
  "warehousePublicId": "c3d4e5f6-...",
  "warehouseName": "Kho chính",
  "quantity": 50.00,
  "syncVersion": 3,
  "lastModifiedAt": "2024-06-01T10:00:00Z",
  "updatedAt": "2024-06-01T10:00:00Z"
}
```

---

## Đối tượng `InventoryTransactionResponse`

Một giao dịch tồn kho (mỗi thay đổi tồn kho đều tạo ra một transaction).

```json
{
  "id": 42,
  "storeId": 1,
  "productPublicId": "b2c3d4e5-...",
  "productName": "Cà phê sữa",
  "warehousePublicId": "c3d4e5f6-...",
  "warehouseName": "Kho chính",
  "type": "ADJUSTMENT",
  "quantity": 10.00,
  "previousQuantity": 40.00,
  "orderPublicId": null,
  "purchaseOrderPublicId": null,
  "note": "Nhập bổ sung tồn kho",
  "createdByUsername": "nguyen.an",
  "createdAt": "2024-06-01T10:00:00Z"
}
```

### Các giá trị `type`

| Giá trị | Quy ước dấu `quantity` | Mô tả |
|:--------|:------|:------|
| `IN` | Luôn dương | Nhập kho — nhận hàng nhập, hủy đơn bán, hoàn tất đơn trả hàng |
| `OUT` | Luôn dương (hướng trừ kho suy từ type) | Xuất kho — tạo đơn bán |
| `TRANSFER` | Delta có dấu — chân xuất âm, chân nhập dương (2 bản ghi/lần chuyển) | Chuyển kho giữa 2 warehouse |
| `ADJUSTMENT` | Delta có dấu — tăng dương, giảm âm | Điều chỉnh thủ công |

> Khi tổng hợp báo cáo, **không** SUM `quantity` trộn lẫn các type — quy ước dấu khác nhau.

---

## GET `/api/stores/{storeId}/inventory`

Lấy danh sách tồn kho của store (bỏ qua bản ghi đã soft delete). Có thể lọc theo kho hàng. Yêu cầu user là **thành viên** của store.

Không phân trang. Sản phẩm chưa từng nhập vào một kho thì không có bản ghi ở kho đó — client coi như tồn kho `0`.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `warehousePublicId` | UUID | ❌ | Lọc tồn kho theo kho hàng cụ thể |

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "publicId": "a1b2c3d4-...",
      "storeId": 1,
      "productPublicId": "b2c3d4e5-...",
      "productName": "Cà phê sữa",
      "warehousePublicId": "c3d4e5f6-...",
      "warehouseName": "Kho chính",
      "quantity": 50.00,
      "syncVersion": 3,
      "lastModifiedAt": "2024-06-01T10:00:00Z",
      "updatedAt": "2024-06-01T10:00:00Z"
    }
  ],
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 404 | `WAREHOUSE_NOT_FOUND` | `warehousePublicId` không thuộc store này |

---

## GET `/api/stores/{storeId}/inventory/transactions`

Lấy lịch sử tất cả giao dịch tồn kho của store, mới nhất trước (`createdAt` giảm dần). Không phân trang. Yêu cầu user là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 42,
      "storeId": 1,
      "productPublicId": "b2c3d4e5-...",
      "productName": "Cà phê sữa",
      "warehousePublicId": "c3d4e5f6-...",
      "warehouseName": "Kho chính",
      "type": "OUT",
      "quantity": 2.00,
      "previousQuantity": 52.00,
      "orderPublicId": "d4e5f6a7-...",
      "purchaseOrderPublicId": null,
      "note": null,
      "createdByUsername": "nguyen.an",
      "createdAt": "2024-06-01T09:30:00Z"
    }
  ],
  "error": null
}
```

> Dấu của `quantity` tùy theo `type` — xem bảng "Các giá trị `type`" ở trên.

Không có lỗi riêng ngoài [Lỗi chung](#lỗi-chung).

---

## POST `/api/stores/{storeId}/inventory/adjust`

Điều chỉnh tồn kho thủ công. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

Tạo ra một `InventoryTransaction` với `type = ADJUSTMENT`. Lượng thay đổi được ghi nhận theo dấu của `quantity`. Nếu sản phẩm chưa có bản ghi tồn kho ở kho này thì tạo mới với tồn ban đầu `0`. Sau khi điều chỉnh, tổng tồn của sản phẩm (`Product.totalStock`) được tính lại.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "productPublicId": "b2c3d4e5-...",
  "warehousePublicId": "c3d4e5f6-...",
  "quantity": 10.00,
  "note": "Nhập bổ sung tồn kho"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `productPublicId` | UUID | ✅ | Public ID của sản phẩm |
| `warehousePublicId` | UUID | ✅ | Public ID của kho hàng |
| `quantity` | number | ✅ | Số lượng thay đổi — dương = nhập thêm, âm = xuất bớt. Phải khác 0 (xem lưu ý bên dưới) |
| `note` | string | ❌ | Ghi chú lý do điều chỉnh |

### Response `200 OK`

Trả về `InventoryTransactionResponse` của giao dịch vừa tạo.

```json
{
  "success": true,
  "data": {
    "id": 43,
    "storeId": 1,
    "productPublicId": "b2c3d4e5-...",
    "productName": "Cà phê sữa",
    "warehousePublicId": "c3d4e5f6-...",
    "warehouseName": "Kho chính",
    "type": "ADJUSTMENT",
    "quantity": 10.00,
    "previousQuantity": 40.00,
    "orderPublicId": null,
    "purchaseOrderPublicId": null,
    "note": "Nhập bổ sung tồn kho",
    "createdByUsername": "nguyen.an",
    "createdAt": "2024-06-01T11:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field bắt buộc bị thiếu |
| 400 | `VALIDATION_ERROR` | Điều chỉnh làm tồn kho âm (`"Adjustment would result in negative stock"`) |
| 404 | `PRODUCT_NOT_FOUND` | Sản phẩm không thuộc business của store |
| 404 | `WAREHOUSE_NOT_FOUND` | Kho hàng không tồn tại trong store này |

> **Lưu ý `quantity = 0`:** endpoint này chưa validate, request đi tới DB và bị CHECK `quantity <> 0` chặn → hiện trả **500 `INTERNAL_ERROR`**. Client không được gửi 0. (`/adjust/bulk` đã chặn sẵn và trả 400.)

---

## POST `/api/stores/{storeId}/inventory/transfer`

Chuyển một sản phẩm từ kho nguồn sang kho đích. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

Chạy trong một transaction, tạo 2 `InventoryTransaction` `type = TRANSFER`: chân xuất (`quantity` âm) ở kho nguồn và chân nhập (`quantity` dương) ở kho đích. Nếu kho đích chưa có bản ghi tồn kho của sản phẩm thì tạo mới. Kho đích phải đang active; kho nguồn inactive vẫn được chuyển ra (để rút hàng trước khi xóa kho).

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "productPublicId": "b2c3d4e5-...",
  "fromWarehousePublicId": "c3d4e5f6-...",
  "toWarehousePublicId": "e5f6a7b8-...",
  "quantity": 5.00,
  "note": "Bổ sung hàng cho chi nhánh"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `productPublicId` | UUID | ✅ | Public ID của sản phẩm |
| `fromWarehousePublicId` | UUID | ✅ | Kho nguồn |
| `toWarehousePublicId` | UUID | ✅ | Kho đích, khác kho nguồn, đang active |
| `quantity` | number | ✅ | Số lượng chuyển, `>= 0.01`, không vượt tồn kho nguồn |
| `note` | string | ❌ | Ghi chú, lưu trên cả 2 transaction |

### Response `200 OK`

Trả về mảng 2 `InventoryTransactionResponse`: `[chân xuất, chân nhập]`.

```json
{
  "success": true,
  "data": [
    {
      "id": 44,
      "storeId": 1,
      "productPublicId": "b2c3d4e5-...",
      "productName": "Cà phê sữa",
      "warehousePublicId": "c3d4e5f6-...",
      "warehouseName": "Kho chính",
      "type": "TRANSFER",
      "quantity": -5.00,
      "previousQuantity": 50.00,
      "orderPublicId": null,
      "purchaseOrderPublicId": null,
      "note": "Bổ sung hàng cho chi nhánh",
      "createdByUsername": "nguyen.an",
      "createdAt": "2024-06-01T12:00:00Z"
    },
    {
      "id": 45,
      "storeId": 1,
      "productPublicId": "b2c3d4e5-...",
      "productName": "Cà phê sữa",
      "warehousePublicId": "e5f6a7b8-...",
      "warehouseName": "Kho chi nhánh",
      "type": "TRANSFER",
      "quantity": 5.00,
      "previousQuantity": 8.00,
      "orderPublicId": null,
      "purchaseOrderPublicId": null,
      "note": "Bổ sung hàng cho chi nhánh",
      "createdByUsername": "nguyen.an",
      "createdAt": "2024-06-01T12:00:00Z"
    }
  ],
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Thiếu field, `quantity < 0.01`, kho nguồn trùng kho đích, kho đích inactive |
| 400 | `INSUFFICIENT_STOCK` | Tồn kho nguồn không đủ |
| 404 | `PRODUCT_NOT_FOUND` | Sản phẩm không thuộc business của store |
| 404 | `WAREHOUSE_NOT_FOUND` | Kho nguồn hoặc kho đích không tồn tại trong store này |
| 404 | `INVENTORY_NOT_FOUND` | Sản phẩm chưa từng có tồn kho ở kho nguồn |

---

## POST `/api/stores/{storeId}/inventory/adjust/bulk`

Điều chỉnh tồn kho nhiều sản phẩm trong **cùng một kho** trong một request (kiểm kê, sửa chênh lệch hàng loạt). Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

**All-or-nothing:** toàn bộ lô chạy trong một transaction — một dòng lỗi thì không dòng nào được ghi. Mỗi dòng tạo một `InventoryTransaction` với `type = ADJUSTMENT`, `note` dùng chung cho cả lô.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "warehousePublicId": "c3d4e5f6-...",
  "note": "Kiểm kho cuối tháng",
  "items": [
    { "productPublicId": "b2c3d4e5-...", "quantity": 20 },
    { "productPublicId": "d4e5f6a7-...", "quantity": -3 }
  ]
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `warehousePublicId` | UUID | ✅ | Kho áp dụng cho mọi dòng |
| `items` | array | ✅ | 1–200 dòng, không trùng `productPublicId` |
| `items[].productPublicId` | UUID | ✅ | Public ID của sản phẩm |
| `items[].quantity` | number | ✅ | Delta có dấu, khác 0 — giống `/adjust`. Client muốn nhập "số đếm thực tế" thì tự tính `counted − current` |
| `note` | string | ❌ | Lý do, áp dụng cho cả lô |

### Response `200 OK`

Trả về mảng `InventoryTransactionResponse`, theo đúng thứ tự `items`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Thiếu field, `items` rỗng hoặc quá 200 dòng, trùng sản phẩm, `quantity = 0`, hoặc một dòng làm tồn kho âm (message có dạng `"<tên> (<SKU>): Adjustment would result in negative stock"`) |
| 404 | `PRODUCT_NOT_FOUND` | Một sản phẩm không thuộc business của store |
| 404 | `WAREHOUSE_NOT_FOUND` | Kho hàng không tồn tại trong store này |
| 429 | `RATE_LIMIT_EXCEEDED` | Quá 10 request / 10 phút mỗi user — `adjust/bulk` và `transfer/bulk` dùng chung quota — kèm header `Retry-After` (giây phải chờ); xem [RATE_LIMITING.md](../RATE_LIMITING.md) |

---

## POST `/api/stores/{storeId}/inventory/transfer/bulk`

Chuyển nhiều sản phẩm từ một kho sang một kho khác trong một request. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

**All-or-nothing:** toàn bộ lô chạy trong một transaction — một dòng không đủ tồn kho nguồn thì không dòng nào được chuyển. Mỗi dòng tạo 2 `InventoryTransaction` `type = TRANSFER` (chân xuất âm ở kho nguồn, chân nhập dương ở kho đích), `note` dùng chung cho cả lô. Kho đích phải đang active; kho nguồn inactive vẫn được chuyển ra.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "fromWarehousePublicId": "c3d4e5f6-...",
  "toWarehousePublicId": "e5f6a7b8-...",
  "note": "Bổ sung hàng cho chi nhánh",
  "items": [
    { "productPublicId": "b2c3d4e5-...", "quantity": 12 },
    { "productPublicId": "d4e5f6a7-...", "quantity": 4 }
  ]
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `fromWarehousePublicId` | UUID | ✅ | Kho nguồn |
| `toWarehousePublicId` | UUID | ✅ | Kho đích, khác kho nguồn, đang active |
| `items` | array | ✅ | 1–200 dòng, không trùng `productPublicId` |
| `items[].productPublicId` | UUID | ✅ | Public ID của sản phẩm |
| `items[].quantity` | number | ✅ | Số lượng chuyển, `>= 0.01`, không vượt tồn kho nguồn |
| `note` | string | ❌ | Ghi chú, áp dụng cho cả lô |

### Response `200 OK`

Trả về mảng `InventoryTransactionResponse`: mỗi dòng của `items` sinh 2 phần tử liên tiếp `[chân xuất, chân nhập]`, theo đúng thứ tự `items`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Thiếu field, `items` rỗng hoặc quá 200 dòng, trùng sản phẩm, kho nguồn trùng kho đích, kho đích inactive |
| 400 | `INSUFFICIENT_STOCK` | Một dòng vượt tồn kho nguồn (message có dạng `"<tên> (<SKU>): Insufficient stock in source warehouse"`) |
| 404 | `PRODUCT_NOT_FOUND` | Một sản phẩm không thuộc business của store |
| 404 | `WAREHOUSE_NOT_FOUND` | Kho nguồn hoặc kho đích không tồn tại trong store này |
| 404 | `INVENTORY_NOT_FOUND` | Sản phẩm chưa từng có tồn kho ở kho nguồn |
| 429 | `RATE_LIMIT_EXCEEDED` | Quá 10 request / 10 phút mỗi user — `adjust/bulk` và `transfer/bulk` dùng chung quota — kèm header `Retry-After` (giây phải chờ); xem [RATE_LIMITING.md](../RATE_LIMITING.md) |
