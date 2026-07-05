# API Reference — Warehouses

Quản lý kho hàng trong một store. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `WarehouseResponse`

Dùng chung cho mọi endpoint trả về thông tin kho hàng.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "storeId": 1,
  "name": "Kho chính",
  "address": "123 Nguyễn Huệ, Q1, TP.HCM",
  "isActive": true,
  "syncVersion": 1,
  "lastModifiedAt": "2024-06-01T10:00:00Z",
  "createdAt": "2024-01-15T08:00:00Z",
  "updatedAt": "2024-06-01T10:00:00Z"
}
```

---

## GET `/api/stores/{storeId}/warehouses`

Lấy danh sách tất cả kho hàng của store. Yêu cầu user là **thành viên** của store.

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
      "id": 1,
      "publicId": "a1b2c3d4-...",
      "storeId": 1,
      "name": "Kho chính",
      "address": "123 Nguyễn Huệ, Q1, TP.HCM",
      "isActive": true,
      "syncVersion": 1,
      "lastModifiedAt": "2024-06-01T10:00:00Z",
      "createdAt": "2024-01-15T08:00:00Z",
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

## GET `/api/stores/{storeId}/warehouses/{publicId}`

Lấy chi tiết một kho hàng. Yêu cầu user là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của kho hàng |

### Response `200 OK`

Trả về `WarehouseResponse`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của store |
| 404 | `WAREHOUSE_NOT_FOUND` | Kho hàng không tồn tại trong store này |

---

## POST `/api/stores/{storeId}/warehouses`

Tạo kho hàng mới trong store. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "name": "Kho phụ",
  "address": "456 Lê Lợi, Q1, TP.HCM",
  "isActive": true
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `name` | string | ✅ | max 100 ký tự |
| `address` | string | ❌ | — |
| `isActive` | boolean | ❌ | Mặc định `true` nếu không truyền |

### Response `201 Created`

Trả về `WarehouseResponse`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `name` bị thiếu hoặc vượt 100 ký tự |
| 402 | `SUBSCRIPTION_LIMIT_EXCEEDED` | Đã đạt giới hạn số kho hàng theo gói |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## PUT `/api/stores/{storeId}/warehouses/{publicId}`

Cập nhật thông tin kho hàng. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của kho hàng |

### Request

Body giống `POST` — xem bảng field ở trên.

### Response `200 OK`

Trả về `WarehouseResponse` sau khi cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `name` bị thiếu hoặc vượt 100 ký tự |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `WAREHOUSE_NOT_FOUND` | Kho hàng không tồn tại trong store này |

---

## DELETE `/api/stores/{storeId}/warehouses/{publicId}`

Xóa kho hàng. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của kho hàng |

### Response `200 OK`

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `WAREHOUSE_NOT_FOUND` | Kho hàng không tồn tại trong store này |
