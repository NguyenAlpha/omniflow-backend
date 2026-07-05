# API Reference — Order

Quản lý đơn bán hàng (order). Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `OrderResponse`

Dùng chung cho mọi endpoint trả về thông tin đơn bán hàng.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "storeId": 1,
  "orderCode": "ORD-2024-001",
  "customerPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "status": "PENDING",
  "subtotal": 1000000.00,
  "discount": 50000.00,
  "discountType": "FIXED",
  "tax": 0.00,
  "totalAmount": 950000.00,
  "paidAmount": 0.00,
  "debtAmount": 950000.00,
  "note": "Khách quen",
  "syncVersion": 1,
  "lastModifiedAt": "2024-01-15T08:00:00Z",
  "createdAt": "2024-01-15T08:00:00Z",
  "updatedAt": "2024-01-15T08:00:00Z",
  "items": [
    {
      "id": 1,
      "publicId": "d4e5f6a7-b8c9-0123-defa-234567890123",
      "productPublicId": "e5f6a7b8-c9d0-1234-efab-345678901234",
      "productName": "Cà phê Arabica",
      "quantity": 2.00,
      "unitPrice": 500000.00,
      "discount": 0.00,
      "discountType": "FIXED",
      "totalPrice": 1000000.00,
      "syncVersion": 1
    }
  ]
}
```

### Các giá trị `status`

| Giá trị | Ý nghĩa |
|:--------|:--------|
| `PENDING` | Đơn mới tạo, chưa hoàn thành |
| `COMPLETED` | Đã hoàn tất |
| `CANCELLED` | Đã huỷ |

### Các giá trị `discountType`

| Giá trị | Ý nghĩa |
|:--------|:--------|
| `FIXED` | Giảm giá theo số tiền cố định |
| `PERCENT` | Giảm giá theo phần trăm |

---

## GET `/api/stores/{storeId}/orders`

Lấy danh sách đơn bán hàng của store theo trang, sắp xếp theo `createdAt` mới nhất. Yêu cầu là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `orderCode` | string | ❌ | Lọc theo mã đơn (tìm kiếm gần đúng) |
| `status` | string | ❌ | Lọc theo trạng thái: `PENDING`, `COMPLETED`, `CANCELLED` |
| `customerPublicId` | UUID | ❌ | Lọc theo khách hàng — chỉ trả về đơn của khách đó |
| `from` | string (`yyyy-MM-dd`) | ❌ | Lọc đơn từ ngày (bao gồm) |
| `to` | string (`yyyy-MM-dd`) | ❌ | Lọc đơn đến ngày (bao gồm) |
| `page` | number | ❌ | Trang hiện tại (bắt đầu từ `0`), mặc định `0` |
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
        "orderCode": "ORD-2024-001",
        "customerPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
        "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
        "status": "PENDING",
        "subtotal": 1000000.00,
        "discount": 50000.00,
        "discountType": "FIXED",
        "tax": 0.00,
        "totalAmount": 950000.00,
        "paidAmount": 0.00,
        "debtAmount": 950000.00,
        "note": "Khách quen",
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
| 404 | `NOT_FOUND` | Không tìm thấy store |

---

## GET `/api/stores/{storeId}/orders/{publicId}`

Lấy chi tiết một đơn bán hàng, bao gồm đầy đủ danh sách sản phẩm. Yêu cầu là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn bán hàng |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "ORD-2024-001",
    "customerPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "PENDING",
    "subtotal": 1000000.00,
    "discount": 50000.00,
    "discountType": "FIXED",
    "tax": 0.00,
    "totalAmount": 950000.00,
    "paidAmount": 0.00,
    "debtAmount": 950000.00,
    "note": "Khách quen",
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z",
    "items": [
      {
        "id": 1,
        "publicId": "d4e5f6a7-b8c9-0123-defa-234567890123",
        "productPublicId": "e5f6a7b8-c9d0-1234-efab-345678901234",
        "productName": "Cà phê Arabica",
        "quantity": 2.00,
        "unitPrice": 500000.00,
        "discount": 0.00,
        "discountType": "FIXED",
        "totalPrice": 1000000.00,
        "syncVersion": 1
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
| 404 | `NOT_FOUND` | Không tìm thấy store hoặc đơn bán hàng |

---

## POST `/api/stores/{storeId}/orders`

Tạo đơn bán hàng mới. Khi tạo, tồn kho được trừ ngay lập tức. Chỉ **OWNER** hoặc **MANAGER** của store mới được tạo.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |

### Request

```json
{
  "customerPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "discount": 50000.00,
  "discountType": "FIXED",
  "tax": 0.00,
  "paidAmount": 300000.00,
  "note": "Khách quen",
  "items": [
    {
      "productPublicId": "e5f6a7b8-c9d0-1234-efab-345678901234",
      "quantity": 2.00,
      "unitPrice": 500000.00,
      "discount": 0.00,
      "discountType": "FIXED"
    }
  ]
}
```

> `orderCode` được backend tự sinh theo format `ORD-XXXXXX` (6 ký tự hex ngẫu nhiên, VD: `ORD-3F9A2B`).

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `customerPublicId` | UUID | ❌ | Khách vãng lai để `null` |
| `warehousePublicId` | UUID | ✅ | |
| `discount` | number | ✅ | Tối thiểu `0.00` |
| `discountType` | string | ✅ | `FIXED` hoặc `PERCENT` |
| `tax` | number | ✅ | Tối thiểu `0.00` |
| `paidAmount` | number | ❌ | Tối thiểu `0.00`, không vượt `totalAmount`. Mặc định `0` |
| `note` | string | ❌ | |
| `items` | array | ✅ | Ít nhất 1 phần tử |
| `items[].productPublicId` | UUID | ✅ | |
| `items[].quantity` | number | ✅ | Tối thiểu `0.01` |
| `items[].unitPrice` | number | ✅ | Tối thiểu `0.00` |
| `items[].discount` | number | ✅ | Tối thiểu `0.00` |
| `items[].discountType` | string | ✅ | `FIXED` hoặc `PERCENT` |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "ORD-2024-001",
    "customerPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "PENDING",
    "subtotal": 1000000.00,
    "discount": 50000.00,
    "discountType": "FIXED",
    "tax": 0.00,
    "totalAmount": 950000.00,
    "paidAmount": 300000.00,
    "debtAmount": 650000.00,
    "note": "Khách quen",
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z",
    "items": [
      {
        "id": 1,
        "publicId": "d4e5f6a7-b8c9-0123-defa-234567890123",
        "productPublicId": "e5f6a7b8-c9d0-1234-efab-345678901234",
        "productName": "Cà phê Arabica",
        "quantity": 2.00,
        "unitPrice": 500000.00,
        "discount": 0.00,
        "discountType": "FIXED",
        "totalPrice": 1000000.00,
        "syncVersion": 1
      }
    ]
  },
  "error": null
}
```

> Đơn mới tạo luôn có `status = PENDING`. `debtAmount = totalAmount - paidAmount`. Nếu không truyền `paidAmount`, mặc định `paidAmount = 0` và `debtAmount = totalAmount`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 400 | `VALIDATION_ERROR` | `paidAmount` vượt quá `totalAmount` |
| 400 | `VALIDATION_ERROR` | Không đủ tồn kho cho sản phẩm |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của store |
| 404 | `NOT_FOUND` | Không tìm thấy store, warehouse, customer hoặc product |

---

## PUT `/api/stores/{storeId}/orders/{publicId}/complete`

Hoàn tất đơn bán hàng. Nếu đơn có `debtAmount > 0` và có khách hàng, phần nợ sẽ được cộng vào `debtBalance` của khách. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn bán hàng |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "ORD-2024-001",
    "customerPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "COMPLETED",
    "subtotal": 1000000.00,
    "discount": 50000.00,
    "discountType": "FIXED",
    "tax": 0.00,
    "totalAmount": 950000.00,
    "paidAmount": 0.00,
    "debtAmount": 950000.00,
    "note": "Khách quen",
    "syncVersion": 2,
    "lastModifiedAt": "2024-01-15T10:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T10:00:00Z",
    "items": []
  },
  "error": null
}
```

> `status` chuyển sang `COMPLETED`. `syncVersion` tăng lên.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `INVALID_STATE` | Đơn đã ở trạng thái `COMPLETED` hoặc `CANCELLED` |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của store |
| 404 | `NOT_FOUND` | Không tìm thấy store hoặc đơn bán hàng |

---

## PUT `/api/stores/{storeId}/orders/{publicId}/pay`

Ghi nhận thanh toán nợ cho đơn bán hàng. Cập nhật `paidAmount` và `debtAmount` trên đơn. Nếu đơn đã `COMPLETED` và có khách hàng, `debtBalance` của khách cũng được giảm tương ứng. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn bán hàng |

### Request

```json
{
  "amount": 300000.00
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `amount` | number | ✅ | Tối thiểu `0.01`, không vượt `debtAmount` hiện tại của đơn |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "ORD-2024-001",
    "customerPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "COMPLETED",
    "subtotal": 1000000.00,
    "discount": 50000.00,
    "discountType": "FIXED",
    "tax": 0.00,
    "totalAmount": 950000.00,
    "paidAmount": 600000.00,
    "debtAmount": 350000.00,
    "note": "Khách quen",
    "syncVersion": 3,
    "lastModifiedAt": "2024-01-15T11:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T11:00:00Z",
    "items": []
  },
  "error": null
}
```

> `paidAmount` tăng lên, `debtAmount` giảm xuống. Nếu đơn đã `COMPLETED` và có khách hàng, `customer.debtBalance` cũng giảm theo `amount`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `amount` nhỏ hơn `0.01` |
| 400 | `INVALID_STATE` | `amount` vượt quá `debtAmount` còn lại của đơn |
| 400 | `INVALID_STATE` | Đơn đang ở trạng thái `CANCELLED` |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của store |
| 404 | `NOT_FOUND` | Không tìm thấy store hoặc đơn bán hàng |

---

## PUT `/api/stores/{storeId}/orders/{publicId}/cancel`

Huỷ đơn bán hàng. Tồn kho của tất cả sản phẩm trong đơn được hoàn trả về kho. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn bán hàng |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "storeId": 1,
    "orderCode": "ORD-2024-001",
    "customerPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "warehousePublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "status": "CANCELLED",
    "subtotal": 1000000.00,
    "discount": 50000.00,
    "discountType": "FIXED",
    "tax": 0.00,
    "totalAmount": 950000.00,
    "paidAmount": 0.00,
    "debtAmount": 950000.00,
    "note": "Khách quen",
    "syncVersion": 2,
    "lastModifiedAt": "2024-01-15T10:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T10:00:00Z",
    "items": [
      {
        "id": 1,
        "publicId": "d4e5f6a7-b8c9-0123-defa-234567890123",
        "productPublicId": "e5f6a7b8-c9d0-1234-efab-345678901234",
        "productName": "Cà phê Arabica",
        "quantity": 2.00,
        "unitPrice": 500000.00,
        "discount": 0.00,
        "discountType": "FIXED",
        "totalPrice": 1000000.00,
        "syncVersion": 1
      }
    ]
  },
  "error": null
}
```

> `status` chuyển sang `CANCELLED`. `syncVersion` tăng lên. Tồn kho được hoàn trả toàn bộ.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `INVALID_STATE` | Đơn đã ở trạng thái `COMPLETED` hoặc `CANCELLED` |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của store |
| 404 | `NOT_FOUND` | Không tìm thấy store hoặc đơn bán hàng |
