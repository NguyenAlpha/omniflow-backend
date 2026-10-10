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

## GET `/api/users/me/memberships`

Lấy danh sách business/store hiện tại của người dùng — **cùng logic và format** với trường
`memberships` trong response đăng nhập (xem [AUTH.md](AUTH.md#trường-memberships-theo-từng-loại-role)).
Dùng để client đồng bộ lại sau khi quyền thay đổi kể từ lúc login (tạo store mới, được thêm/bỏ khỏi
store, đổi role, store bị xóa...) mà không phải gọi `refresh` (refresh xoay vòng token).

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "businessId": 5,
      "businessName": "Coffee An",
      "stores": [
        { "storeId": 10, "storeName": "Chi nhánh 1", "role": "ROLE_OWNER", "positionTitle": null },
        { "storeId": 11, "storeName": "Chi nhánh 2", "role": "ROLE_OWNER", "positionTitle": null }
      ]
    }
  ],
  "error": null
}
```

> Mảng rỗng nếu user chưa thuộc business nào (VD vừa đăng ký, hoặc chỉ là SUPER_ADMIN).

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |

---

## GET `/api/users/lookup`

Tra cứu user theo `username` (khớp chính xác) để lấy `userId` — dùng khi owner thêm
thành viên business/store. Chỉ cần đăng nhập (thao tác thêm thành viên mới là owner-only).

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:-------|
| `username` | string | ✅ | Username cần tra (khớp chính xác) |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "userId": 6,
    "username": "vo.em",
    "fullName": "Võ Thị Em",
    "isActive": true
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 404 | `USER_NOT_FOUND` | Không có user nào khớp username |

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
| 400 | `USERNAME_TAKEN` | Username đã được tài khoản khác sử dụng |
| 400 | `EMAIL_TAKEN` | Email đã được tài khoản khác sử dụng |
| 400 | `VALIDATION_ERROR` | Hiếm: 2 request song song cùng lọt bước kiểm tra trùng → unique index DB chặn (message `Username or email already taken`, không phân biệt được field nào) |

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
| 400 | `INVALID_CURRENT_PASSWORD` | `currentPassword` không đúng — refresh token **không** bị thu hồi |
| 429 | `RATE_LIMIT_EXCEEDED` | Quá 5 lần đổi mật khẩu / 10 phút mỗi user (`RATE_LIMIT_CHANGE_PASSWORD_USER_*`) — kèm header `Retry-After` (giây phải chờ); xem [RATE_LIMITING.md](../RATE_LIMITING.md) |
