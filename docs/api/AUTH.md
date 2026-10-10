# API Reference — Auth

Xác thực người dùng. `register`, `login`, `refresh` là endpoint **public** — không cần JWT.
`logout` yêu cầu JWT.

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

## POST `/api/auth/register`

Tạo tài khoản mới. Trả về access token, refresh token và thông tin user ngay sau khi đăng ký.

### Request

```json
{
  "username": "nguyen.an",
  "email": "an@coffee.vn",
  "password": "matkhau123",
  "fullName": "Nguyễn Văn An",
  "phone": "0901234567"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `username` | string | ✅ | max 50 ký tự, unique, chỉ chứa `a-z A-Z 0-9 . _ -` (không cho `@` — tránh trùng định dạng email) |
| `email` | string | ✅ | format email hợp lệ, max 100 ký tự, unique |
| `password` | string | ✅ | 6–72 ký tự (BCrypt giới hạn 72 bytes) |
| `fullName` | string | ✅ | max 200 ký tự |
| `phone` | string | ❌ | 8–20 ký tự, chỉ chứa `0-9`, `+`, `-`, `(`, `)`, space |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": {
      "id": 2,
      "username": "nguyen.an",
      "email": "an@coffee.vn",
      "fullName": "Nguyễn Văn An",
      "phone": "0901234567",
      "isActive": true
    },
    "memberships": [],
    "refreshToken": "3f9a1c...e7b2 (64 ký tự hex)"
  },
  "error": null
}
```

> `memberships` luôn rỗng sau khi đăng ký — user chưa thuộc business nào.
> Client gọi tiếp `POST /api/businesses/default` (kèm access token) để tạo bộ mặc định:
> business + gói FREE + store đầu tiên + kho đầu tiên, user là `OWNER`
> (xem [BUSINESS.md](BUSINESS.md)). Sau đó gọi `refresh` để nhận `memberships` mới.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 400 | `VALIDATION_ERROR` | Username hoặc email đã tồn tại (`Username or email already taken`) |
| 429 | `RATE_LIMIT_EXCEEDED` | Quá 5 lần đăng ký / phút từ cùng IP |

---

## POST `/api/auth/login`

Đăng nhập. Có thể dùng username hoặc email.

### Request

```json
{
  "usernameOrEmail": "nguyen.an",
  "password": "matkhau123"
}
```

| Field | Type | Bắt buộc |
|:------|:-----|:--------:|
| `usernameOrEmail` | string | ✅ |
| `password` | string | ✅ |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": {
      "id": 2,
      "username": "nguyen.an",
      "email": "an@coffee.vn",
      "fullName": "Nguyễn Văn An",
      "phone": "0901234567",
      "isActive": true
    },
    "memberships": [
      {
        "businessId": 1,
        "businessName": "Coffee Chain",
        "stores": [
          {
            "storeId": 1,
            "storeName": "Chi nhánh Q1",
            "role": "ROLE_OWNER",
            "positionTitle": null
          },
          {
            "storeId": 2,
            "storeName": "Chi nhánh Q3",
            "role": "ROLE_OWNER",
            "positionTitle": null
          }
        ]
      }
    ],
    "refreshToken": "3f9a1c...e7b2 (64 ký tự hex)"
  },
  "error": null
}
```

### Trường `memberships` theo từng loại role

**OWNER / BUSINESS_MANAGER** (role cấp business) — stores = tất cả stores (chưa xóa) trong
business, `role` = role cấp business, `positionTitle = null`:
```json
"memberships": [
  {
    "businessId": 1,
    "businessName": "Coffee Chain",
    "stores": [
      { "storeId": 1, "storeName": "Chi nhánh Q1", "role": "ROLE_OWNER", "positionTitle": null },
      { "storeId": 2, "storeName": "Chi nhánh Q3", "role": "ROLE_OWNER", "positionTitle": null }
    ]
  }
]
```

Trợ lý cấp business có cùng cấu trúc với `"role": "ROLE_BUSINESS_MANAGER"`.

**MANAGER/STAFF** — stores = chỉ những store user là member, role và positionTitle theo từng store:
```json
"memberships": [
  {
    "businessId": 1,
    "businessName": "Coffee Chain",
    "stores": [
      { "storeId": 1, "storeName": "Chi nhánh Q1", "role": "ROLE_MANAGER", "positionTitle": "Trưởng ca" },
      { "storeId": 2, "storeName": "Chi nhánh Q3", "role": "ROLE_STAFF",   "positionTitle": null }
    ]
  }
]
```

**SUPER_ADMIN / user chưa có business** — memberships rỗng:
```json
"memberships": []
```

**User thuộc nhiều business** — mỗi business là 1 entry (entry cấp business đứng trước, sau đó
tới các entry MANAGER/STAFF gom theo business):
```json
"memberships": [
  { "businessId": 1, "businessName": "Coffee Chain", "stores": [...] },
  { "businessId": 2, "businessName": "Bakery XYZ",   "stores": [...] }
]
```

> Chỉ tính các role đang active (`is_active = true`) và chưa bị xóa.

### Trường `accessToken`

JWT (HS256) có payload:
```json
{
  "sub": "nguyen.an",
  "userId": 2,
  "roles": [],
  "iat": 1748736000,
  "exp": 1748739600
}
```

> `roles` chỉ chứa global roles đang active (`ROLE_SUPER_ADMIN`, `ROLE_SUPPORT`). Với user thường, `roles = []`.
> Role cấp business/store **không** nằm trong JWT — server tra DB (có cache Redis) ở mỗi request.
> `expiresIn` tính bằng giây = `jwt.expiration / 1000` (mặc định `JWT_EXPIRATION=3600000` ms → 3600 = 1 giờ).

Gửi kèm mọi request cần xác thực:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

### Trường `refreshToken`

Chuỗi ngẫu nhiên 64 ký tự hex (không phải JWT), sống 30 ngày (`jwt.refresh-token-expiration-days`), lưu trong
bảng `refresh_tokens`. Dùng với `POST /api/auth/refresh` khi access token hết hạn.
Mỗi lần login/register tạo một refresh token mới.

### Giới hạn đăng nhập sai

- Theo tài khoản: tối đa **5 lần sai mật khẩu / 15 phút** (`rate-limit.login-account.*`).
  Username và email của cùng một user dùng chung bucket.
- Chỉ sai mật khẩu mới bị tính; đăng nhập vào tài khoản bị khóa không trừ lượt.
- Khi đã hết lượt, mọi lần đăng nhập vào tài khoản đó (kể cả đúng mật khẩu) đều bị từ chối
  `429` cho tới khi bucket nạp lại.
- Ngoài ra còn quota theo IP: 10 request login / phút.

Response `429` có header `Retry-After` (số giây phải chờ), `RateLimit-Limit`,
`RateLimit-Remaining`, `RateLimit-Reset`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `usernameOrEmail` hoặc `password` bị bỏ trống |
| 401 | `INVALID_CREDENTIALS` | Sai username/email hoặc mật khẩu (`Invalid username or password`) |
| 401 | `INVALID_CREDENTIALS` | Tài khoản bị vô hiệu hoá (`Account is disabled`) |
| 429 | `RATE_LIMIT_EXCEEDED` | Hết lượt đăng nhập sai của tài khoản, hoặc vượt quota login theo IP |

---

## POST `/api/auth/refresh`

Đổi refresh token lấy access token mới. Áp dụng **token rotation**: refresh token gửi lên bị
thu hồi, response trả về một refresh token mới — client phải lưu đè token mới.

### Request

```json
{
  "refreshToken": "3f9a1c...e7b2 (64 ký tự hex)"
}
```

| Field | Type | Bắt buộc |
|:------|:-----|:--------:|
| `refreshToken` | string | ✅ |

### Response `200 OK`

Cùng cấu trúc với response của `login` (access token mới, `memberships` tính lại theo quyền
hiện tại, `refreshToken` mới). Vì vậy `refresh` cũng là cách lấy lại `memberships` sau khi
quyền thay đổi (VD: vừa tạo business mặc định).

### Phát hiện dùng lại token (reuse detection)

Nếu gửi lên một refresh token **đã bị thu hồi** (đã dùng rồi), server coi là token bị đánh cắp
và **thu hồi toàn bộ** refresh token của user đó — mọi phiên khác đều phải đăng nhập lại.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `refreshToken` bị bỏ trống |
| 401 | `REFRESH_TOKEN_INVALID` | Token không tồn tại (`Invalid refresh token`) |
| 401 | `REFRESH_TOKEN_INVALID` | Token đã được dùng — toàn bộ token của user bị thu hồi (`Refresh token already used`) |
| 401 | `REFRESH_TOKEN_EXPIRED` | Token quá 30 ngày (`Refresh token expired`) |
| 401 | `REFRESH_TOKEN_INVALID` | Tài khoản đã bị khóa hoặc xóa — toàn bộ token của user bị thu hồi (`User account is disabled`) |
| 429 | `RATE_LIMIT_EXCEEDED` | Quá 20 request refresh / phút từ cùng IP |

---

## POST `/api/auth/logout`

Đăng xuất: thu hồi **toàn bộ** refresh token của user (đăng xuất mọi thiết bị).
Yêu cầu header `Authorization: Bearer <accessToken>`; không có body.

> Access token hiện tại không bị thu hồi — vẫn dùng được tới khi hết hạn (tối đa 1 giờ).
> Client nên tự xóa access token và refresh token đã lưu.

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
| 401 | `UNAUTHORIZED` | Thiếu hoặc sai access token |

---

## Các thao tác khác thu hồi refresh token

Ngoài `logout`, toàn bộ refresh token của user cũng bị thu hồi khi:
- User đổi mật khẩu (`PATCH /api/users/me/password` — xem [USER.md](USER.md))
- Admin khóa hoặc xóa tài khoản (xem [ADMIN_USER.md](ADMIN_USER.md))

---

## Cách frontend dùng `memberships`

`memberships` dùng để client tự chọn business/store context sau khi login — không cần gọi thêm API.

```typescript
// Chọn business/store đầu tiên tự động
const firstBusiness = memberships[0];
const firstStore    = firstBusiness?.stores[0];

localStorage.setItem("auth_business_id", firstBusiness.businessId);
localStorage.setItem("auth_store_id",    firstStore.storeId);
```

Sau đó mọi API catalog dùng `businessId`, mọi API transactional dùng `storeId`.
Xem chi tiết tại [web/docs](../../../apps/web/docs).
