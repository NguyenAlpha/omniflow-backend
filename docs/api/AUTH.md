# API Reference — Auth

Xác thực người dùng. Tất cả endpoint trong nhóm này đều **public** — không cần JWT.

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

Tạo tài khoản mới. Trả về JWT và thông tin user ngay sau khi đăng ký.

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
| `username` | string | ✅ | max 50 ký tự, unique |
| `email` | string | ✅ | format email hợp lệ, max 100 ký tự, unique |
| `password` | string | ✅ | 6–100 ký tự |
| `fullName` | string | ✅ | max 200 ký tự |
| `phone` | string | ❌ | 8–20 ký tự, chỉ chứa `0-9`, `+`, `-`, `(`, `)`, space |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 86400,
    "user": {
      "id": 2,
      "publicId": "a1b2c3d4-...",
      "username": "nguyen.an",
      "email": "an@coffee.vn",
      "fullName": "Nguyễn Văn An",
      "phone": "0901234567",
      "isActive": true
    },
    "memberships": []
  },
  "error": null
}
```

> `memberships` luôn rỗng sau khi đăng ký — user chưa thuộc business nào.
> Business được tạo riêng qua `POST /api/businesses`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 400 | `VALIDATION_ERROR` | Username hoặc email đã tồn tại |

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
    "expiresIn": 86400,
    "user": {
      "id": 2,
      "publicId": "a1b2c3d4-...",
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
    ]
  },
  "error": null
}
```

### Trường `memberships` theo từng loại role

**OWNER** — stores = tất cả stores trong business, role=ROLE_OWNER, positionTitle=null:
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

**User thuộc nhiều business** — mỗi business là 1 entry:
```json
"memberships": [
  { "businessId": 1, "businessName": "Coffee Chain", "stores": [...] },
  { "businessId": 2, "businessName": "Bakery XYZ",   "stores": [...] }
]
```

### Trường `accessToken`

JWT có payload:
```json
{
  "sub": "nguyen.an",
  "userId": 2,
  "roles": [],
  "iat": 1748736000,
  "exp": 1748822400
}
```

> `roles` chỉ chứa global roles (`SUPER_ADMIN`, `SUPPORT`). Với user thường, `roles = []`.
> `expiresIn` tính bằng giây (86400 = 24 giờ).

Gửi kèm mọi request cần xác thực:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `usernameOrEmail` hoặc `password` bị bỏ trống |
| 401 | `INVALID_CREDENTIALS` | Sai username/email hoặc mật khẩu |
| 401 | `INVALID_CREDENTIALS` | Tài khoản bị vô hiệu hoá (`is_active=false`) |

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
