# API Reference — Admin User Management

Quản lý tài khoản người dùng ở cấp hệ thống. Tất cả endpoint đều **yêu cầu JWT** với role `SUPER_ADMIN` — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `UserAdminResponse`

Dùng chung cho mọi endpoint trả về thông tin người dùng.

```json
{
  "id": 1,
  "username": "nguyen.an",
  "email": "nguyen.an@example.com",
  "fullName": "Nguyễn Văn An",
  "phone": "0901234567",
  "isActive": true,
  "createdAt": "2024-01-15T08:00:00Z",
  "deletedAt": null
}
```

> `deletedAt` — không null nếu tài khoản đã bị xóa mềm (soft delete).

---

## Đối tượng `PagedResult<UserAdminResponse>`

```json
{
  "content": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8
}
```

---

## GET `/api/admin/users`

Lấy danh sách người dùng với tìm kiếm và phân trang. Yêu cầu role **SUPER_ADMIN**.

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `q` | string | ❌ | Tìm theo username, email hoặc tên đầy đủ (không phân biệt hoa thường) |
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
        "username": "nguyen.an",
        "email": "nguyen.an@example.com",
        "fullName": "Nguyễn Văn An",
        "phone": "0901234567",
        "isActive": true,
        "createdAt": "2024-01-15T08:00:00Z",
        "deletedAt": null
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `FORBIDDEN` | Không phải SUPER_ADMIN |

---

## PATCH `/api/admin/users/{userId}`

Cập nhật thông tin profile của người dùng. Yêu cầu role **SUPER_ADMIN**.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `userId` | number | ID của user |

### Request

Dùng chung DTO `UpdateProfileRequest` với `PATCH /api/users/me` — **ghi đè toàn bộ**, không phải
partial update: phải gửi đủ các field bắt buộc (giữ nguyên giá trị cũ nếu không muốn đổi);
`phone` bỏ trống/null sẽ xóa số điện thoại.

```json
{
  "username": "nguyen.an",
  "email": "nguyen.an@example.com",
  "fullName": "Nguyễn Văn An (Updated)",
  "phone": "0907654321"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `username` | string | ✅ | Không được để trống, tối đa 50 ký tự, chỉ chứa `a-z A-Z 0-9 . _ -` (không cho `@`) |
| `email` | string | ✅ | Không được để trống, format email hợp lệ, tối đa 100 ký tự |
| `fullName` | string | ✅ | Không được để trống, tối đa 200 ký tự |
| `phone` | string | ❌ | Tối đa 20 ký tự |

### Response `200 OK`

Trả về `UserAdminResponse` sau khi cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ |
| 400 | `USERNAME_TAKEN` / `EMAIL_TAKEN` | Username / email đã được tài khoản khác sử dụng |
| 403 | `FORBIDDEN` | Không phải SUPER_ADMIN |
| 404 | `USER_NOT_FOUND` | User không tồn tại |

---

## PATCH `/api/admin/users/{userId}/status`

Kích hoạt hoặc khóa tài khoản người dùng. Yêu cầu role **SUPER_ADMIN**.

User bị khóa (`isActive = false`) sẽ không thể đăng nhập. Các session JWT đang hoạt động bị vô hiệu qua Redis blacklist.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `userId` | number | ID của user |

### Request

```json
{
  "isActive": false
}
```

| Field | Type | Bắt buộc |
|:------|:-----|:--------:|
| `isActive` | boolean | ✅ |

### Response `200 OK`

Trả về `UserAdminResponse` với `isActive` đã được cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `isActive` bị thiếu |
| 403 | `FORBIDDEN` | Không phải SUPER_ADMIN |
| 404 | `USER_NOT_FOUND` | User không tồn tại |

---

## DELETE `/api/admin/users/{userId}`

Xóa tài khoản người dùng. Yêu cầu role **SUPER_ADMIN**.

Đây là soft delete — dữ liệu không bị xóa vật lý khỏi DB, `deletedAt` được ghi nhận. User sẽ không thể đăng nhập sau khi xóa.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `userId` | number | ID của user |

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
| 403 | `FORBIDDEN` | Không phải SUPER_ADMIN |
| 404 | `USER_NOT_FOUND` | User không tồn tại |
