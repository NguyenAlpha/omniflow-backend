# API Reference — Payments

Ghi nhận và tra cứu các giao dịch thu chi tại store. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `PaymentResponse`

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "storeId": 1,
  "customerPublicId": "b2c3d4e5-...",
  "customerName": "Nguyễn Văn An",
  "supplierPublicId": null,
  "supplierName": null,
  "amount": 200000.00,
  "paymentMethod": "CASH",
  "note": "Khách trả nợ tháng 6",
  "syncVersion": 1,
  "lastModifiedAt": "2024-06-01T10:00:00Z",
  "createdAt": "2024-06-01T10:00:00Z"
}
```

> `customerPublicId` và `supplierPublicId` xác định chiều giao dịch:
> - `customerPublicId` có giá trị → **Thu vào** (khách hàng trả nợ cho store)
> - `supplierPublicId` có giá trị → **Chi ra** (store trả nợ cho nhà cung cấp)
> - Cả hai `null` → **Thu/chi nội bộ** (không liên kết đối tác)

`customerName` và `supplierName` là tên đối tác tương ứng; field không áp dụng sẽ là `null`.

### Các giá trị `paymentMethod`

| Giá trị | Mô tả |
|:--------|:------|
| `CASH` | Tiền mặt |
| `BANK_TRANSFER` | Chuyển khoản ngân hàng |
| `CREDIT_CARD` | Thẻ tín dụng |
| `DEBIT_CARD` | Thẻ ghi nợ |
| `MOBILE_PAYMENT` | Ví điện tử / QR |
| `OTHER` | Phương thức khác |

---

## Đối tượng `PaymentPageResult`

Kết quả phân trang kèm tổng hợp số tiền.

```json
{
  "content": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 45,
  "totalPages": 3,
  "totalIncome": 5000000.00,
  "totalExpense": 2000000.00
}
```

| Field | Type | Mô tả |
|:------|:-----|:------|
| `content` | array | Danh sách `PaymentResponse` trong trang hiện tại |
| `page` | number | Trang hiện tại (bắt đầu từ 0) |
| `size` | number | Số bản ghi mỗi trang |
| `totalElements` | number | Tổng số giao dịch khớp bộ lọc |
| `totalPages` | number | Tổng số trang |
| `totalIncome` | number | Tổng thu vào theo bộ lọc hiện tại |
| `totalExpense` | number | Tổng chi ra theo bộ lọc hiện tại |

---

## GET `/api/stores/{storeId}/payments`

Tìm kiếm và lọc danh sách giao dịch. Yêu cầu user là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `direction` | string | ❌ | `INCOME` hoặc `EXPENSE` — bỏ trống để lấy tất cả |
| `method` | string | ❌ | Lọc theo phương thức thanh toán (xem enum trên) |
| `from` | string | ❌ | Ngày bắt đầu, format `yyyy-MM-dd` |
| `to` | string | ❌ | Ngày kết thúc, format `yyyy-MM-dd` |
| `page` | number | ❌ | Trang (mặc định 0) |
| `size` | number | ❌ | Số bản ghi mỗi trang (mặc định 20) |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "publicId": "a1b2c3d4-...",
        "storeId": 1,
        "customerPublicId": "b2c3d4e5-...",
        "customerName": "Nguyễn Văn An",
        "supplierPublicId": null,
        "supplierName": null,
        "amount": 200000.00,
        "paymentMethod": "CASH",
        "note": "Khách trả nợ tháng 6",
        "syncVersion": 1,
        "lastModifiedAt": "2024-06-01T10:00:00Z",
        "createdAt": "2024-06-01T10:00:00Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "totalIncome": 200000.00,
    "totalExpense": 0.00
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `FORBIDDEN` | Không phải thành viên của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## GET `/api/stores/{storeId}/payments/{publicId}`

Lấy chi tiết một giao dịch. Yêu cầu user là **thành viên** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của giao dịch |

### Response `200 OK`

Trả về `PaymentResponse`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `FORBIDDEN` | Không phải thành viên của store |
| 404 | `PAYMENT_NOT_FOUND` | Giao dịch không tồn tại trong store này |

---

## POST `/api/stores/{storeId}/payments`

Ghi nhận một giao dịch thu hoặc chi. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

Chiều giao dịch được xác định tự động:
- Cung cấp `customerPublicId` → **Thu vào**, trừ công nợ khách hàng
- Cung cấp `supplierPublicId` → **Chi ra**, trừ công nợ nhà cung cấp
- Không cung cấp cả hai → ghi nhận thu/chi tổng quát, không ảnh hưởng công nợ

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "customerPublicId": "b2c3d4e5-...",
  "supplierPublicId": null,
  "paidAmount": 200000.00,
  "paymentMethod": "CASH",
  "note": "Khách trả nợ tháng 6"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `customerPublicId` | UUID | ❌ | Public ID của khách hàng (thu vào) |
| `supplierPublicId` | UUID | ❌ | Public ID của nhà cung cấp (chi ra) |
| `paidAmount` | number | ✅ | Số tiền — tối thiểu 0.01 |
| `paymentMethod` | string | ✅ | Phương thức thanh toán (xem enum trên) |
| `note` | string | ❌ | Ghi chú |

### Response `201 Created`

Trả về `PaymentResponse`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field bắt buộc bị thiếu hoặc giá trị không hợp lệ |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |
| 404 | `CUSTOMER_NOT_FOUND` | Khách hàng không tồn tại trong business này |
| 404 | `SUPPLIER_NOT_FOUND` | Nhà cung cấp không tồn tại trong business này |
