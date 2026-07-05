# JWT Token Lifecycle

Mô tả vòng đời của JWT access token và refresh token — từ lúc được tạo ra đến khi hết hạn —
bao gồm cấu trúc payload, luồng refresh, và các giới hạn thiết kế.

---

## 1. Vòng đời tổng quan

```
Login / Register
      │
      ▼
  Access token (24h) + Refresh token (30 ngày)
      │
      │  Client lưu cả hai token
      │
      ▼
  Access token còn hạn ──────────────────────────────────────────────────┐
      │                                                                   │
      │  Authorization: Bearer <access_token>                            │
      │  BearerTokenAuthenticationFilter validate (0 DB call)            │
      │                                                                   │
      ▼                                                                   │
  Access token hết hạn (sau 24h)                                        │
      │                                                                   │
      ▼                                                                   │
  Client gửi POST /api/auth/refresh { refreshToken: "..." }              │
      │                                                                   │
      ├── Refresh token hợp lệ → New access token + New refresh token ───┘
      │   (token rotation: refresh token cũ bị revoke)
      │
      └── Refresh token hết hạn / không hợp lệ → 401 → Login lại
```

---

## 2. Cấu trúc JWT access token payload

```json
{
  "sub":    "nguyen.van.a",
  "userId": 42,
  "roles":  ["SUPER_ADMIN"],
  "iat":    1748000000,
  "exp":    1748086400
}
```

| Claim | Kiểu | Mô tả |
|:---|:---|:---|
| `sub` | String | Username — đọc bởi `UserPrincipalConverter` qua `jwt.getSubject()` |
| `userId` | Long | Internal DB ID — dùng để build `UserPrincipal` và query DB khi cần |
| `roles` | List\<String\> | Global roles của user — chỉ `SUPER_ADMIN` hoặc `SUPPORT`; rỗng với user thường |
| `iat` | Unix epoch | Thời điểm token được cấp |
| `exp` | Unix epoch | Thời điểm token hết hạn = `iat` + 86400 giây (24h) |

**Business/store-scoped roles (`OWNER`, `MANAGER`, `STAFF`) không có trong token.**
- `OWNER` — business-scoped, check qua `BusinessAccessEvaluator` hoặc `StoreAccessEvaluator` (DB) mỗi request.
- `MANAGER`, `STAFF` — store-scoped, check qua `StoreAccessEvaluator` (Redis → DB) mỗi request.

---

## 3. Luồng issue token (login / register)

```
AuthService.buildAuthResponse(user)
    │
    ├── RefreshTokenService.create(userId)
    │   └── Generate 64-char opaque token (2x UUID without dashes)
    │   └── INSERT refresh_tokens (token, user_id, expires_at = now + 30 days)
    │   └── Trả về token value
    │
    └── buildBundle(user, rtValue)
        ├── Query DB: findByUserIdAndBusinessIsNullAndStoreIsNullAndDeletedAtIsNull(userId)
        │   └── lấy global roles (SUPER_ADMIN, SUPPORT) để nhúng vào token
        │
        ├── jwtService.generateToken(user, { userId, roles })
        │   └── HS256 JWT, exp = now + 24h
        │
        └── Trả về AuthResponse {
              accessToken, tokenType, expiresIn,
              user, memberships,
              refreshToken  ← opaque token, lưu phía client
            }
```

---

## 4. Luồng validate access token (mỗi request)

```
BearerTokenAuthenticationFilter nhận "Authorization: Bearer <token>"
    │
    ├── NimbusJwtDecoder.decode(token)
    │   ├── Verify chữ ký HMAC-SHA256 → JwtException nếu bị giả mạo
    │   └── Kiểm tra exp < now        → JwtException nếu hết hạn
    │
    ├── Token valid:
    │   └── UserPrincipalConverter.convert(jwt)
    │       ├── username ← jwt.getSubject()
    │       ├── userId   ← jwt.getClaim("userId")  (normalize Integer/Long → Long)
    │       ├── roles    ← jwt.getClaim("roles")   → ["SUPER_ADMIN"] hoặc []
    │       └── set SecurityContext với UserPrincipal(userId, username, roles) + authorities
    │
    └── Token invalid → JwtException bị bắt → 401 Unauthorized
```

---

## 5. Luồng refresh token

```
POST /api/auth/refresh { "refreshToken": "<64-char opaque token>" }
    │
    └── RefreshTokenService.rotate(tokenValue)
        │
        ├── SELECT refresh_tokens WHERE token = ?
        │   └── Không tìm thấy → InvalidTokenException (REFRESH_TOKEN_INVALID) → 401
        │
        ├── Token đã bị revoke (revokedAt IS NOT NULL)?
        │   └── Reuse detected — có thể bị đánh cắp
        │       → revokeAllByUserId (vô hiệu toàn bộ token của user)
        │       → InvalidTokenException (REFRESH_TOKEN_INVALID) → 401
        │
        ├── Token đã hết hạn (expiresAt < now)?
        │   └── SET revokedAt = now
        │       → InvalidTokenException (REFRESH_TOKEN_EXPIRED) → 401
        │
        └── Token hợp lệ:
            ├── SET old_token.revokedAt = now  ← revoke cái cũ
            ├── INSERT new refresh_token       ← issue cái mới (30 ngày)
            └── Trả về AuthResponse mới (access token 24h + refresh token mới)
```

**Rotation:** Mỗi lần refresh, refresh token cũ bị revoke, token mới được cấp.
Client phải lưu token mới sau mỗi lần refresh.

---

## 6. Luồng logout

```
POST /api/auth/logout  (yêu cầu Bearer token hợp lệ)
    │
    └── AuthService.logout(userId)
        └── RefreshTokenService.revokeAll(userId)
            └── UPDATE refresh_tokens SET revoked_at = now
                WHERE user_id = ? AND revoked_at IS NULL
```

Sau logout: toàn bộ refresh token bị revoke. Access token hiện tại vẫn hợp lệ
cho đến khi hết hạn (tối đa 24h) — đây là trade-off chấp nhận được với JWT stateless.

---

## 7. Giới hạn thiết kế cần biết

### 7a. Access token không thể thu hồi trước hạn

JWT là stateless — server không lưu danh sách token đã cấp.
Khi cần vô hiệu hóa access token:
- Token vẫn hợp lệ cho đến khi hết hạn (tối đa 24h)

Các trường hợp cụ thể:

| Sự kiện | Hành vi hiện tại |
|:---|:---|
| User bị deactivate (`isActive = false`) | Token vẫn pass filter. Chỉ bị chặn ở `DaoAuthProvider` nếu login lại |
| User bị soft-delete | Tương tự — access token cũ vẫn hoạt động trong 24h |
| Global role bị thu hồi | Role cũ vẫn còn trong token — có hiệu lực đến khi hết hạn |

> Giải pháp nếu cần revoke access token ngay: dùng Redis blacklist lưu `jti` của token bị thu hồi.

### 7b. Global role thay đổi không phản ánh ngay

Nếu user được cấp hoặc thu hồi `SUPER_ADMIN`:
- Access token hiện tại **không thay đổi** — vẫn chứa roles cũ
- Phải refresh hoặc login lại để lấy token mới với roles mới

---

## 8. Hành vi khi access token hết hạn

```
Client gửi request với token đã hết hạn
    │
    ├── BearerTokenAuthenticationFilter → 401 Unauthorized
    │
    └── Client nên:
        1. Intercept 401
        2. Gọi POST /api/auth/refresh với refresh token còn hạn
        3. Lưu access token mới + refresh token mới
        4. Retry request gốc
        5. Nếu refresh cũng 401 → redirect về màn hình login
```

---

## 9. Cấu hình

| Property | Giá trị mặc định | Ý nghĩa |
|:---|:---|:---|
| `jwt.secret` | Base64-encoded string | HMAC-SHA256 signing key — phải đủ 256-bit sau decode |
| `jwt.expiration` | `86400000` (ms) | TTL access token = 24 giờ |
| `jwt.refresh-token-expiration-days` | `30` | TTL refresh token = 30 ngày |

> `jwt.secret` phải được thay bằng giá trị ngẫu nhiên mạnh trong production.
> Không commit secret thật vào source code.
