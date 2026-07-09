# API Reference — User

Quản lý thông tin tài khoản của người dùng đang đăng nhập. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `UserSummaryResponse`

Dùng chung cho các endpoint trả về thông tin người dùng.

```json
{
  "id": 2,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "username": "nguyen.an",
  "email": "an@coffee.vn",
  "fullName": "Nguyễn Văn An",
  "phone": "0901234567",
  "isActive": true
}
```

---

## GET `/api/users/me`

Lấy thông tin profile của người dùng hiện tại.

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 2,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "username": "nguyen.an",
    "email": "an@coffee.vn",
    "fullName": "Nguyễn Văn An",
    "phone": "0901234567",
    "isActive": true
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |

---

## PATCH `/api/users/me`

Cập nhật thông tin profile của người dùng hiện tại.

### Request

```json
{
  "username": "nguyen.an",
  "email": "an@coffee.vn",
  "fullName": "Nguyễn Văn An",
  "phone": "0901234567"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `username` | string | ✅ | Không được để trống, tối đa 50 ký tự, chỉ chứa `a-z A-Z 0-9 . _ -` (không cho `@`) |
| `email` | string | ✅ | Không được để trống, format email hợp lệ, tối đa 100 ký tự |
| `fullName` | string | ✅ | Không được để trống, tối đa 200 ký tự |
| `phone` | string | ❌ | Tối đa 20 ký tự |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 2,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "username": "nguyen.an",
    "email": "an@coffee.vn",
    "fullName": "Nguyễn Văn An",
    "phone": "0901234567",
    "isActive": true
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 409 | `CONFLICT` | Username hoặc email đã được sử dụng bởi tài khoản khác |

---

## PATCH `/api/users/me/password`

Đổi mật khẩu của người dùng hiện tại.

### Request

```json
{
  "currentPassword": "matkhau_hien_tai",
  "newPassword": "matkhau_moi_123"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `currentPassword` | string | ✅ | Không được để trống |
| `newPassword` | string | ✅ | Không được để trống, 6–72 ký tự (BCrypt giới hạn 72 bytes) |

> Sau khi đổi mật khẩu thành công, **toàn bộ refresh token của user bị thu hồi** — các phiên
> khác phải đăng nhập lại (đá kẻ xâm nhập đang giữ refresh token cũ ra khỏi hệ thống).

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
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 400 | `INVALID_CREDENTIALS` | `currentPassword` không đúng |
