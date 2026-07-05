# API Reference — Return Orders

Quản lý đơn trả hàng. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

---

## Response envelope

Mọi API đều trả về cùng một cấu trúc bọc ngoài:

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

Khi lỗi:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "Mô tả lỗi",
    "field": "tên field nếu là lỗi validation, ngược lại null"
  }
}
```

---

## Đối tượng `ReturnOrderResponse`

Dùng chung cho mọi endpoint trả về thông tin đơn trả hàng.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "storeId": 1,
  "returnCode": "RET-001",
  "originalOrderPublicId": "b2c3d4e5-...",
  "warehousePublicId": "c3d4e5f6-...",
  "status": "PENDING",
  "reason": "Hàng bị lỗi",
  "totalRefund": 150000.00,
  "refundMethod": "CASH",
  "note": "Đổi lại cho khách",
  "syncVersion": 1,
  "lastModifiedAt": "2024-06-01T10:00:00Z",
  "createdAt": "2024-06-01T10:00:00Z",
  "updatedAt": "2024-06-01T10:00:00Z",
  "items": [
    {
      "id": 1,
      "productPublicId": "d4e5f6a7-...",
      "productName": "Cà phê sữa",
      "quantity": 2,
      "unitPrice": 75000.00,
      "totalRefund": 150000.00
    }
  ]
}
```

### Các giá trị `status`

| Giá trị | Mô tả |
|:--------|:------|
| `PENDING` | Đơn trả vừa tạo, chưa xử lý |
| `COMPLETED` | Đã hoàn tất — tồn kho đã được cộng lại, công nợ đã được điều chỉnh |
| `CANCELLED` | Đã hủy — không có tác động nào |

### Các giá trị `refundMethod`

| Giá trị | Mô tả |
|:--------|:------|
| `CASH` | Hoàn tiền mặt |
| `BANK_TRANSFER` | Hoàn qua chuyển khoản |
| `STORE_CREDIT` | Cộng vào số dư tài khoản khách |

---

## GET `/api/stores/{storeId}/returns`

Lấy danh sách tất cả đơn trả hàng của store. Yêu cầu user là **thành viên** của store.

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
      "returnCode": "RET-001",
      "originalOrderPublicId": "b2c3d4e5-...",
      "warehousePublicId": "c3d4e5f6-...",
      "status": "PENDING",
      "reason": "Hàng bị lỗi",
      "totalRefund": 150000.00,
      "refundMethod": "CASH",
      "note": null,
      "syncVersion": 1,
      "lastModifiedAt": "2024-06-01T10:00:00Z",
      "createdAt": "2024-06-01T10:00:00Z",
      "updatedAt": "2024-06-01T10:00:00Z",
      "items": [ ... ]
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

## GET `/api/stores/{storeId}/returns/{publicId}`

Lấy chi tiết một đơn trả hàng. Yêu cầu user là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn trả hàng |

### Response `200 OK`

Trả về `ReturnOrderResponse`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của store |
| 404 | `RETURN_ORDER_NOT_FOUND` | Đơn trả không tồn tại trong store này |

---

## POST `/api/stores/{storeId}/returns`

Tạo đơn trả hàng mới. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

Đơn mới tạo ở trạng thái `PENDING` — chưa ảnh hưởng đến tồn kho hay công nợ cho đến khi được complete.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "returnCode": "RET-001",
  "originalOrderPublicId": "b2c3d4e5-...",
  "warehousePublicId": "c3d4e5f6-...",
  "reason": "Hàng bị lỗi",
  "refundMethod": "CASH",
  "note": "Đổi lại cho khách",
  "items": [
    {
      "productPublicId": "d4e5f6a7-...",
      "quantity": 2,
      "unitPrice": 75000.00
    }
  ]
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `returnCode` | string | ❌ | Mã đơn trả tùy chọn |
| `originalOrderPublicId` | UUID | ❌ | Public ID của đơn bán gốc nếu có |
| `warehousePublicId` | UUID | ❌ | Kho nhận hàng trả về |
| `reason` | string | ❌ | Lý do trả hàng |
| `refundMethod` | string | ❌ | `CASH`, `BANK_TRANSFER`, `STORE_CREDIT` |
| `note` | string | ❌ | Ghi chú thêm |
| `items` | array | ❌ | Danh sách sản phẩm trả |
| `items[].productPublicId` | UUID | ✅ | Public ID của sản phẩm |
| `items[].quantity` | number | ✅ | Số lượng trả |
| `items[].unitPrice` | number | ✅ | Đơn giá hoàn trả |

### Response `201 Created`

Trả về `ReturnOrderResponse` với `status = "PENDING"`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## PUT `/api/stores/{storeId}/returns/{publicId}/complete`

Hoàn tất đơn trả hàng. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

**Side effects khi complete:**
- Tồn kho sản phẩm được **cộng lại** theo từng item
- Công nợ khách hàng được **giảm** tương ứng (nếu đơn gốc có khách hàng và dư nợ)

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn trả hàng |

### Response `200 OK`

Trả về `ReturnOrderResponse` với `status = "COMPLETED"`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Đơn không ở trạng thái PENDING |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `RETURN_ORDER_NOT_FOUND` | Đơn trả không tồn tại |

---

## PUT `/api/stores/{storeId}/returns/{publicId}/cancel`

Hủy đơn trả hàng. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

Chỉ hủy được khi đơn ở trạng thái `PENDING`. Không ảnh hưởng đến tồn kho hay công nợ.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn trả hàng |

### Response `200 OK`

Trả về `ReturnOrderResponse` với `status = "CANCELLED"`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Đơn không ở trạng thái PENDING |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `RETURN_ORDER_NOT_FOUND` | Đơn trả không tồn tại |
