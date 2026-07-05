# API Reference — Purchase Order

Quản lý đơn nhập hàng (purchase order). Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `PurchaseOrderResponse`

Dùng chung cho mọi endpoint trả về thông tin đơn nhập hàng.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "storeId": 1,
  "orderCode": "PO-2024-001",
  "supplierPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "supplierName": "Nhà cung cấp A",
  "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "warehouseName": "Kho chính",
  "status": "PENDING",
  "totalAmount": 5000000.00,
  "paidAmount": 0.00,
  "debtAmount": 5000000.00,
  "note": "Giao hàng buổi sáng",
  "syncVersion": 1,
  "lastModifiedAt": "2024-01-15T08:00:00Z",
  "createdAt": "2024-01-15T08:00:00Z",
  "updatedAt": "2024-01-15T08:00:00Z",
  "items": [
    {
      "id": 1,
      "productPublicId": "d4e5f6a7-b8c9-0123-defa-234567890123",
      "productName": "Cà phê Arabica",
      "quantity": 10.00,
      "unitPrice": 500000.00,
      "totalPrice": 5000000.00
    }
  ]
}
```

### Các giá trị `status`

| Giá trị | Ý nghĩa |
|:--------|:--------|
| `PENDING` | Đơn mới tạo, chờ nhận hàng |
| `RECEIVED` | Đã nhận hàng |
| `CANCELLED` | Đã huỷ |

---

## GET `/api/stores/{storeId}/purchases`

Lấy danh sách đơn nhập hàng của store. Yêu cầu là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `orderCode` | string | ❌ | Lọc theo mã đơn (tìm kiếm gần đúng) |
| `status` | string | ❌ | Lọc theo trạng thái: `PENDING`, `RECEIVED`, `CANCELLED` |
| `from` | string (`yyyy-MM-dd`) | ❌ | Lọc đơn từ ngày (bao gồm) |
| `to` | string (`yyyy-MM-dd`) | ❌ | Lọc đơn đến ngày (bao gồm) |
| `page` | number | ❌ | Trang hiện tại, mặc định `0` |
| `size` | number | ❌ | Số bản ghi mỗi trang, mặc định `20` |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        "storeId": 1,
        "orderCode": "PO-2024-001",
        "supplierPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
        "supplierName": "Nhà cung cấp A",
        "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
        "warehouseName": "Kho chính",
        "status": "PENDING",
        "totalAmount": 5000000.00,
        "paidAmount": 0.00,
        "debtAmount": 5000000.00,
        "note": "Giao hàng buổi sáng",
        "syncVersion": 1,
        "lastModifiedAt": "2024-01-15T08:00:00Z",
        "createdAt": "2024-01-15T08:00:00Z",
        "updatedAt": "2024-01-15T08:00:00Z",
        "items": []
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  },
  "error": null
}
```

> `items` luôn là mảng rỗng trong danh sách — dùng endpoint lấy chi tiết để lấy danh sách sản phẩm.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của store |

---

## GET `/api/stores/{storeId}/purchases/{publicId}`

Lấy chi tiết một đơn nhập hàng. Yêu cầu là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn nhập hàng |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "PO-2024-001",
    "supplierPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "PENDING",
    "totalAmount": 5000000.00,
    "paidAmount": 0.00,
    "debtAmount": 5000000.00,
    "note": "Giao hàng buổi sáng",
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z",
    "items": [
      {
        "id": 1,
        "productPublicId": "d4e5f6a7-b8c9-0123-defa-234567890123",
        "productName": "Cà phê Arabica",
        "quantity": 10.00,
        "unitPrice": 500000.00,
        "totalPrice": 5000000.00
      }
    ]
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của store |
| 404 | `NOT_FOUND` | Không tìm thấy đơn nhập hàng |

---

## POST `/api/stores/{storeId}/purchases`

Tạo đơn nhập hàng mới. Chỉ **OWNER** hoặc **MANAGER** của store mới được tạo.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |

### Request

```json
{
  "supplierPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "paidAmount": 1000000.00,
  "paymentMethod": "CASH",
  "note": "Giao hàng buổi sáng",
  "items": [
    {
      "productPublicId": "d4e5f6a7-b8c9-0123-defa-234567890123",
      "quantity": 10.00,
      "unitPrice": 500000.00
    }
  ]
}
```

> `orderCode` được backend tự sinh theo format `PO-XXXXXX` (6 ký tự hex ngẫu nhiên, VD: `PO-3F9A2B`).

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `supplierPublicId` | UUID | ✅ | |
| `warehousePublicId` | UUID | ✅ | |
| `paidAmount` | number | ❌ | Tối thiểu `0.00`, mặc định `0` |
| `paymentMethod` | string | ❌ | `CASH` hoặc `TRANSFER`, mặc định `CASH` |
| `note` | string | ❌ | |
| `items` | array | ✅ | Ít nhất 1 phần tử |
| `items[].productPublicId` | UUID | ✅ | |
| `items[].quantity` | number | ✅ | Tối thiểu `0.01` |
| `items[].unitPrice` | number | ✅ | Tối thiểu `0.00` |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "PO-2024-001",
    "supplierPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "PENDING",
    "totalAmount": 5000000.00,
    "paidAmount": 0.00,
    "debtAmount": 5000000.00,
    "note": "Giao hàng buổi sáng",
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z",
    "items": [
      {
        "id": 1,
        "productPublicId": "d4e5f6a7-b8c9-0123-defa-234567890123",
        "productName": "Cà phê Arabica",
        "quantity": 10.00,
        "unitPrice": 500000.00,
        "totalPrice": 5000000.00
      }
    ]
  },
  "error": null
}
```

> Đơn mới tạo luôn có `status = PENDING`. Payment record **chưa** được tạo ở bước này — sẽ được tạo khi `receive`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 400 | `ILLEGAL_ARGUMENT` | `paidAmount` vượt quá `totalAmount` |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của store |
| 404 | `NOT_FOUND` | Không tìm thấy supplier, warehouse hoặc product |

---

## PUT `/api/stores/{storeId}/purchases/{publicId}/receive`

Xác nhận đã nhận hàng cho đơn nhập. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn nhập hàng |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "PO-2024-001",
    "supplierPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "RECEIVED",
    "totalAmount": 5000000.00,
    "paidAmount": 0.00,
    "debtAmount": 5000000.00,
    "note": "Giao hàng buổi sáng",
    "syncVersion": 2,
    "lastModifiedAt": "2024-01-15T10:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T10:00:00Z",
    "items": [ ... ]
  },
  "error": null
}
```

> `status` chuyển sang `RECEIVED`. Tồn kho được cộng. `supplier.debtBalance` tăng theo `debtAmount`. Nếu `paidAmount > 0`, một Payment record được INSERT.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của store |
| 404 | `NOT_FOUND` | Không tìm thấy đơn nhập hàng |
| 409 | `INVALID_STATE` | Đơn không ở trạng thái `PENDING` |

---

## PUT `/api/stores/{storeId}/purchases/{publicId}/pay`

Ghi nhận thanh toán công nợ cho đơn nhập. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn nhập hàng |

### Request

```json
{
  "amount": 1000000.00
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `amount` | number | ✅ | Tối thiểu `0.01`, không được vượt `debtAmount` |

### Response `200 OK`

Trả về `PurchaseOrderResponse` với `paidAmount` và `debtAmount` đã cập nhật.

### Lưu ý

- Đơn `PENDING`: chỉ cập nhật `paidAmount`/`debtAmount` trên đơn, không INSERT Payment.
- Đơn `RECEIVED`: cập nhật `paidAmount`/`debtAmount`, giảm `supplier.debtBalance`, INSERT Payment.
- Không thể thanh toán đơn đã `CANCELLED`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `ILLEGAL_ARGUMENT` | `amount` vượt quá `debtAmount` hoặc đơn đã `CANCELLED` |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của store |
| 404 | `NOT_FOUND` | Không tìm thấy đơn nhập hàng |

---

## PUT `/api/stores/{storeId}/purchases/{publicId}/cancel`

Huỷ đơn nhập hàng. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn nhập hàng |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "PO-2024-001",
    "supplierPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "CANCELLED",
    "totalAmount": 5000000.00,
    "paidAmount": 0.00,
    "debtAmount": 5000000.00,
    "note": "Giao hàng buổi sáng",
    "syncVersion": 2,
    "lastModifiedAt": "2024-01-15T10:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T10:00:00Z",
    "items": [ ... ]
  },
  "error": null
}
```

> `status` chuyển sang `CANCELLED`. `syncVersion` tăng lên.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của store |
| 404 | `NOT_FOUND` | Không tìm thấy đơn nhập hàng |
| 409 | `INVALID_STATE` | Đơn không ở trạng thái `PENDING` |
