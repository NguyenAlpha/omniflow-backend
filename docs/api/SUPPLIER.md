# API Reference — Suppliers

Quản lý nhà cung cấp và công nợ. Nhà cung cấp thuộc phạm vi **business** (dùng chung cho mọi store trong cùng business). Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `SupplierResponse`

Dùng chung cho mọi endpoint trả về thông tin nhà cung cấp.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "businessId": 1,
  "code": "NCC-001",
  "name": "Công ty TNHH Thực phẩm XYZ",
  "phone": "0901234567",
  "email": "contact@xyz.vn",
  "address": "123 Lê Lợi, Q1, TP.HCM",
  "debtBalance": 5000000.00,
  "syncVersion": 2,
  "lastModifiedAt": "2024-06-01T10:00:00Z",
  "createdAt": "2024-01-15T08:00:00Z",
  "updatedAt": "2024-06-01T10:00:00Z"
}
```

> `debtBalance` — số tiền business đang nợ nhà cung cấp. Tăng khi tạo đơn nhập hàng, giảm khi ghi nhận thanh toán.

---

## Đối tượng `PagedResult<SupplierResponse>`

Kết quả phân trang (dùng cho endpoint search).

```json
{
  "content": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 45,
  "totalPages": 3
}
```

---

## GET `/api/businesses/{businessId}/suppliers`

Lấy toàn bộ danh sách nhà cung cấp của business (không phân trang). Yêu cầu user là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "publicId": "a1b2c3d4-...",
      "businessId": 1,
      "code": "NCC-001",
      "name": "Công ty TNHH Thực phẩm XYZ",
      "phone": "0901234567",
      "email": "contact@xyz.vn",
      "address": "123 Lê Lợi, Q1, TP.HCM",
      "debtBalance": 5000000.00,
      "syncVersion": 2,
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
| 403 | `ACCESS_DENIED` | Không phải thành viên của business |
| 404 | `BUSINESS_NOT_FOUND` | Business không tồn tại |

---

## GET `/api/businesses/{businessId}/suppliers/search`

Tìm kiếm nhà cung cấp theo tên với phân trang. Yêu cầu user là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Query parameters

| Parameter | Type | Bắt buộc | Ràng buộc | Mô tả |
|:----------|:-----|:--------:|:----------|:------|
| `q` | string | ✅ | — | Từ khóa tìm theo tên |
| `page` | number | ❌ | min 0, mặc định 0 | Trang |
| `size` | number | ❌ | 1–100, mặc định 20 | Số bản ghi mỗi trang |

Kết quả được sắp xếp theo tên tăng dần.

### Response `200 OK`

Trả về `PagedResult<SupplierResponse>`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `q` bị thiếu |
| 403 | `ACCESS_DENIED` | Không phải thành viên của business |

---

## GET `/api/businesses/{businessId}/suppliers/{publicId}`

Lấy chi tiết một nhà cung cấp. Yêu cầu user là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của nhà cung cấp |

### Response `200 OK`

Trả về `SupplierResponse`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của business |
| 404 | `SUPPLIER_NOT_FOUND` | Nhà cung cấp không tồn tại trong business này |

---

## POST `/api/businesses/{businessId}/suppliers`

Tạo nhà cung cấp mới. Yêu cầu user là **OWNER** hoặc **MANAGER** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Request

```json
{
  "code": "NCC-001",
  "name": "Công ty TNHH Thực phẩm XYZ",
  "phone": "0901234567",
  "email": "contact@xyz.vn",
  "address": "123 Lê Lợi, Q1, TP.HCM"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `code` | string | ✅ | max 20 ký tự |
| `name` | string | ✅ | max 200 ký tự |
| `phone` | string | ❌ | 8–20 ký tự, chỉ chứa `0-9`, `+`, `-`, `(`, `)`, space |
| `email` | string | ❌ | format email hợp lệ, max 100 ký tự |
| `address` | string | ❌ | max 2000 ký tự |

### Response `201 Created`

Trả về `SupplierResponse` với `debtBalance = 0`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `BUSINESS_NOT_FOUND` | Business không tồn tại |
| 409 | `SUPPLIER_CODE_DUPLICATE` | `code` đã tồn tại trong business |

---

## PUT `/api/businesses/{businessId}/suppliers/{publicId}`

Cập nhật thông tin nhà cung cấp. Yêu cầu user là **OWNER** hoặc **MANAGER** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của nhà cung cấp |

### Request

Body giống `POST` — xem bảng field ở trên.

### Response `200 OK`

Trả về `SupplierResponse` sau khi cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `SUPPLIER_NOT_FOUND` | Nhà cung cấp không tồn tại |
| 409 | `SUPPLIER_CODE_DUPLICATE` | `code` đã tồn tại (khi đổi sang code khác) |

---

## PUT `/api/businesses/{businessId}/suppliers/{publicId}/pay`

Ghi nhận thanh toán công nợ cho nhà cung cấp — giảm `debtBalance`. Yêu cầu user là **OWNER** hoặc **MANAGER** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của nhà cung cấp |

### Request

```json
{
  "amount": 2000000.00,
  "paymentMethod": "BANK_TRANSFER",
  "storeId": 1
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `amount` | number | ✅ | Số tiền thanh toán — tối thiểu 0.01 |
| `paymentMethod` | string | ❌ | Enum `PaymentMethod`: `CASH`, `BANK_TRANSFER`, `CREDIT_CARD`, `DEBIT_CARD`, `MOBILE_PAYMENT`, `OTHER`. Giá trị khác → 400. Mặc định `CASH` |
| `storeId` | number | ❌ | Store gắn phiếu chi (phải thuộc business). Không gửi → gán vào store đầu tiên của business (kèm log warn phía server) |

### Response `200 OK`

Trả về `SupplierResponse` với `debtBalance` đã được giảm.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `amount` thiếu hoặc nhỏ hơn 0.01; `paymentMethod` không thuộc enum |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `SUPPLIER_NOT_FOUND` | Nhà cung cấp không tồn tại |
| 404 | `STORE_NOT_FOUND` | `storeId` không tồn tại hoặc không thuộc business |

---

## DELETE `/api/businesses/{businessId}/suppliers/{publicId}`

Xóa nhà cung cấp. Yêu cầu user là **OWNER** hoặc **MANAGER** của business.

> Nhà cung cấp còn công nợ (`debtBalance != 0`) không thể xóa — phải tất toán trước
> để khoản nợ không biến mất khỏi báo cáo công nợ.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của nhà cung cấp |

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
| 400 | `VALIDATION_ERROR` | Nhà cung cấp còn công nợ chưa tất toán |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `SUPPLIER_NOT_FOUND` | Nhà cung cấp không tồn tại |
