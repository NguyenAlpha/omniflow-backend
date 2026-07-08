# API Reference — Inventory

Xem và điều chỉnh tồn kho sản phẩm theo kho hàng. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

Lấy danh sách tồn kho của store. Có thể lọc theo kho hàng. Yêu cầu user là **thành viên** của store.

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
| 403 | `ACCESS_DENIED` | Không phải thành viên của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## GET `/api/stores/{storeId}/inventory/transactions`

Lấy lịch sử tất cả giao dịch tồn kho của store. Yêu cầu user là **thành viên** của store.

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

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## POST `/api/stores/{storeId}/inventory/adjust`

Điều chỉnh tồn kho thủ công. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

Tạo ra một `InventoryTransaction` với `type = ADJUSTMENT`. Lượng thay đổi được ghi nhận theo dấu của `quantity`.

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
| `quantity` | number | ✅ | Số lượng thay đổi — dương = nhập thêm, âm = xuất bớt (khác 0, DB có CHECK `quantity <> 0`) |
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
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |
| 404 | `PRODUCT_NOT_FOUND` | Sản phẩm không tồn tại trong store này |
| 404 | `WAREHOUSE_NOT_FOUND` | Kho hàng không tồn tại trong store này |
