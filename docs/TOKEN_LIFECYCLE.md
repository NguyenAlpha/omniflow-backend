# JWT Token Lifecycle

Mô tả vòng đời của JWT token — từ lúc được tạo ra đến khi hết hạn —
bao gồm cấu trúc payload, giới hạn thiết kế, và hành vi khi token hết hạn.

---

## 1. Vòng đời tổng quan

```
Login / Register
      │
      ▼
  Token issued
  TTL: 24h
      │
      │  Client lưu token, gửi kèm mỗi request
      │  Authorization: Bearer <token>
      │
      ▼
  Token active ──────────────────────────────────────────────────┐
      │                                                           │
      │  JwtAuthFilter validate mỗi request (0 DB call)          │
      │                                                           │
      ▼                                                           │
  Token expired (sau 24h)                                        │
      │                                                           │
      ▼                                                           │
  401 Unauthorized ← Client phải login lại để lấy token mới ────┘
```

---

## 2. Cấu trúc JWT payload

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
| `sub` | String | Username — dùng để identify user (đọc bởi `JwtService.extractUsername`) |
| `userId` | Long | Internal DB ID — dùng để build `UserPrincipal` và query DB khi cần |
| `roles` | List\<String\> | Global roles của user — chỉ `SUPER_ADMIN` hoặc `SUPPORT`; rỗng với user thường |
| `iat` | Unix epoch | Thời điểm token được cấp |
| `exp` | Unix epoch | Thời điểm token hết hạn = `iat` + 86400 giây (24h) |

**Store-scoped roles (`OWNER`, `MANAGER`, `STAFF`) không có trong token.**
Chúng phụ thuộc context store, được kiểm tra riêng qua `StoreAccessEvaluator` (Redis → DB) mỗi request.

---

## 3. Luồng issue token

```
AuthService.buildAuthResponse(user)
    │
    ├── Query DB: findByUserIdAndStoreIsNullAndDeletedAtIsNull(userId)
    │   └── lấy global roles (SUPER_ADMIN, SUPPORT) để nhúng vào token
    │   └── user thường: roles = []
    │
    └── jwtService.generateToken(user, { userId, roles })
        │
        ├── Jwts.builder()
        │   ├── .claims({ userId, roles })   ← custom claims
        │   ├── .subject(username)           ← claim "sub"
        │   ├── .issuedAt(now)
        │   ├── .expiration(now + 86400000ms)
        │   └── .signWith(HMAC-SHA256 key)
        │
        └── trả về "eyJhbGci....eyJ1c2VyS..."
```

---

## 4. Luồng validate token (mỗi request)

```
JwtAuthFilter nhận "Authorization: Bearer <token>"
    │
    ├── jwtService.isTokenValid(token)
    │   ├── Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token)
    │   │   ├── Verify chữ ký HMAC → SignatureException nếu bị giả mạo
    │   │   └── Parse payload → ExpiredJwtException nếu exp < now
    │   └── return !isTokenExpired
    │
    ├── Token valid:
    │   ├── extractUsername(token) → "nguyen.van.a"
    │   ├── extractUserId(token)   → 42
    │   ├── extractRoles(token)    → ["SUPER_ADMIN"]  hoặc []
    │   └── set SecurityContext với UserPrincipal(42, "nguyen.van.a") + authorities
    │
    └── Token invalid → không set SecurityContext → request tiếp tục nhưng sẽ bị 401
```

---

## 5. Giới hạn thiết kế cần biết

### 5a. Không có refresh token

Project hiện tại **không có** refresh token. Khi token hết hạn sau 24h:
- Client nhận `401 Unauthorized`
- Client phải gọi `POST /api/auth/login` để lấy token mới
- Không có cơ chế tự động renew

### 5b. Token không thể thu hồi trước hạn

JWT là stateless — server không lưu danh sách token đã cấp.
Khi cần vô hiệu hóa token (VD: user đổi password, bị deactivate):
- Token vẫn hợp lệ cho đến khi hết hạn (tối đa 24h)
- Server không có cách biết token đó "nên bị từ chối"

Các trường hợp cụ thể:

| Sự kiện | Hành vi hiện tại |
|:---|:---|
| User bị deactivate (`isActive = false`) | Token vẫn pass JwtAuthFilter (không check DB). Chỉ bị chặn ở DaoAuthProvider nếu login lại |
| User bị soft-delete | Tương tự — token cũ vẫn hoạt động trong 24h |
| Global role bị thu hồi | Role cũ vẫn còn trong token — có hiệu lực đến khi hết hạn |

> Đây là **trade-off chấp nhận được** với scope Phase 1. Giải pháp nếu cần revoke:
> dùng Redis blacklist lưu `jti` (JWT ID) của token bị thu hồi, check trong JwtAuthFilter.

### 5c. Global role thay đổi không phản ánh ngay

Nếu user được cấp hoặc thu hồi `SUPER_ADMIN`:
- Token hiện tại **không thay đổi** — vẫn chứa roles cũ
- Phải login lại để lấy token mới với roles mới

---

## 6. Hành vi khi token hết hạn

```
Client gửi request với token đã hết hạn
    │
    ├── JwtAuthFilter: jwtService.isTokenValid(token)
    │   └── Jwts.parser().parseSignedClaims(token) → ném ExpiredJwtException
    │   └── isTokenValid() catch → return false
    │
    ├── JwtAuthFilter bỏ qua (không set SecurityContext)
    │
    ├── AuthorizationFilter: endpoint cần auth, SecurityContext trống → từ chối
    │
    ├── ExceptionTranslationFilter → authenticationEntryPoint
    │   └── response.sendError(401, "Unauthorized")
    │
    └── Client nhận:
        { "status": 401, "error": "Unauthorized", "path": "/api/stores/1" }
        (Spring Boot error format — không phải ApiResult)
```

**Client nên xử lý:** intercept mọi response `401` → xóa token cũ → redirect về màn hình login.

---

## 7. Cấu hình

| Property | Giá trị mặc định | Ý nghĩa |
|:---|:---|:---|
| `jwt.secret` | Base64-encoded string | HMAC-SHA256 signing key — phải đủ 256-bit sau decode |
| `jwt.expiration` | `86400000` (ms) | TTL token = 24 giờ |

> `jwt.secret` phải được thay bằng giá trị ngẫu nhiên mạnh trong production.
> Không commit secret thật vào source code.
