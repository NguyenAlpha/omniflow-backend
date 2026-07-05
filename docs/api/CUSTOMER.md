# API Reference — Customer

Quản lý khách hàng trong business. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `CustomerResponse`

Dùng chung cho mọi endpoint trả về thông tin khách hàng.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "businessId": 1,
  "code": "KH-001",
  "name": "Nguyễn Văn An",
  "phone": "0901234567",
  "email": "an@coffee.vn",
  "address": "123 Nguyễn Huệ, Q1, TP.HCM",
  "debtBalance": 0.00,
  "syncVersion": 1,
  "lastModifiedAt": "2024-01-15T08:00:00Z",
  "createdAt": "2024-01-15T08:00:00Z",
  "updatedAt": "2024-01-15T08:00:00Z"
}
```

---

## GET `/api/businesses/{businessId}/customers`

Lấy toàn bộ danh sách khách hàng (không phân trang). Yêu cầu là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "businessId": 1,
      "code": "KH-001",
      "name": "Nguyễn Văn An",
      "phone": "0901234567",
      "email": "an@coffee.vn",
      "address": "123 Nguyễn Huệ, Q1, TP.HCM",
      "debtBalance": 0.00,
      "syncVersion": 1,
      "lastModifiedAt": "2024-01-15T08:00:00Z",
      "createdAt": "2024-01-15T08:00:00Z",
      "updatedAt": "2024-01-15T08:00:00Z"
    }
  ],
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của business |

---

## GET `/api/businesses/{businessId}/customers/search`

Tìm kiếm khách hàng có phân trang, sắp xếp theo `name` tăng dần. Yêu cầu là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `q` | string | ✅ | Từ khoá tìm kiếm theo tên, mã, hoặc số điện thoại |
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
        "businessId": 1,
        "code": "KH-001",
        "name": "Nguyễn Văn An",
        "phone": "0901234567",
        "email": "an@coffee.vn",
        "address": "123 Nguyễn Huệ, Q1, TP.HCM",
        "debtBalance": 0.00,
        "syncVersion": 1,
        "lastModifiedAt": "2024-01-15T08:00:00Z",
        "createdAt": "2024-01-15T08:00:00Z",
        "updatedAt": "2024-01-15T08:00:00Z"
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

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Thiếu tham số `q` |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của business |

---

## GET `/api/businesses/{businessId}/customers/{publicId}`

Lấy chi tiết một khách hàng. Yêu cầu là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của khách hàng |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 1,
    "code": "KH-001",
    "name": "Nguyễn Văn An",
    "phone": "0901234567",
    "email": "an@coffee.vn",
    "address": "123 Nguyễn Huệ, Q1, TP.HCM",
    "debtBalance": 150000.00,
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của business |
| 404 | `NOT_FOUND` | Không tìm thấy khách hàng |

---

## POST `/api/businesses/{businessId}/customers`

Tạo khách hàng mới. Chỉ **OWNER** hoặc **MANAGER** của business mới được tạo.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |

### Request

```json
{
  "code": "KH-001",
  "name": "Nguyễn Văn An",
  "phone": "0901234567",
  "email": "an@coffee.vn",
  "address": "123 Nguyễn Huệ, Q1, TP.HCM"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `code` | string | ✅ | Không được để trống, tối đa 20 ký tự |
| `name` | string | ✅ | Không được để trống, tối đa 200 ký tự |
| `phone` | string | ❌ | 8–20 ký tự, chỉ chứa `0-9`, `+`, `-`, `(`, `)`, space |
| `email` | string | ❌ | Format email hợp lệ, tối đa 100 ký tự |
| `address` | string | ❌ | Tối đa 2000 ký tự |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 1,
    "code": "KH-001",
    "name": "Nguyễn Văn An",
    "phone": "0901234567",
    "email": "an@coffee.vn",
    "address": "123 Nguyễn Huệ, Q1, TP.HCM",
    "debtBalance": 0.00,
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z"
  },
  "error": null
}
```

> Khách hàng mới tạo luôn có `debtBalance = 0`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của business |
| 409 | `CONFLICT` | Mã khách hàng (`code`) đã tồn tại trong business |

---

## PUT `/api/businesses/{businessId}/customers/{publicId}`

Cập nhật thông tin khách hàng. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của khách hàng |

### Request

Cùng cấu trúc với `POST`, xem bảng fields ở trên.

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 1,
    "code": "KH-001",
    "name": "Nguyễn Văn An (Updated)",
    "phone": "0901234567",
    "email": "an.updated@coffee.vn",
    "address": "456 Lê Lợi, Q1, TP.HCM",
    "debtBalance": 150000.00,
    "syncVersion": 2,
    "lastModifiedAt": "2024-02-01T09:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-02-01T09:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của business |
| 404 | `NOT_FOUND` | Không tìm thấy khách hàng |
| 409 | `CONFLICT` | Mã khách hàng (`code`) đã được dùng bởi khách hàng khác |

---

## DELETE `/api/businesses/{businessId}/customers/{publicId}`

Xoá khách hàng. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của khách hàng |

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
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của business |
| 404 | `NOT_FOUND` | Không tìm thấy khách hàng |
