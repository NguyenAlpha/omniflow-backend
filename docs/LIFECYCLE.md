# Vòng đời hệ thống OmniFlow

---

## 1. Application Lifecycle (khởi động)

```
Application Start
│
├── Spring Boot khởi động
│
├── IoC Container khởi tạo — scan @Component, @Service, @Configuration
│
├── Singleton Beans được tạo (1 lần, sống toàn app)
│   │
│   ├── JwtService               — parse / generate JWT
│   ├── JwtAuthFilter            — filter xác thực mỗi request
│   ├── StoreAccessEvaluator     — kiểm tra store-scoped role (Redis + DB)
│   ├── UserDetailsService       — load User từ DB (chỉ dùng khi login)
│   ├── AuthenticationProvider   — DaoAuthenticationProvider (BCrypt verify)
│   ├── AuthenticationManager    — điều phối các AuthenticationProvider
│   ├── Controllers / Services   — xử lý business logic
│   └── StringRedisTemplate      — Redis client
│
├── SecurityFilterChain được build — thứ tự filter cố định cho toàn app
│   │
│   ├── JwtAuthFilter                        ← custom, thêm vào trước filter mặc định
│   ├── UsernamePasswordAuthenticationFilter ← có trong chain nhưng không dùng (form login tắt)
│   ├── AnonymousAuthenticationFilter        ← set anonymous nếu chưa có auth
│   ├── ExceptionTranslationFilter           ← bắt 401/403, trả JSON error
│   └── AuthorizationFilter                  ← kiểm tra quyền truy cập endpoint
│
│   Lưu ý: CsrfFilter bị tắt (csrf.disable() trong SecurityConfig)
│
└── Application READY — bắt đầu nhận request
```

---

## 2. Luồng Login (`POST /api/auth/login`)

> Đây là REST endpoint — **không** đi qua `UsernamePasswordAuthenticationFilter`.
> Client gửi JSON body, `AuthController` xử lý trực tiếp.

```
HTTP POST /api/auth/login  {"usernameOrEmail": "...", "password": "..."}
│
├── Tomcat nhận request
│
├── DelegatingFilterProxy → SecurityFilterChain
│
├── JwtAuthFilter
│   └── Không có header "Authorization: Bearer ..." → bỏ qua, đi tiếp
│
├── AuthorizationFilter
│   └── /api/auth/** là permitAll() → cho qua, không cần xác thực
│
├── DispatcherServlet → AuthController.login()
│
├── AuthService.login()
│   │
│   ├── authenticationManager.authenticate(username, password)
│   │   │
│   │   └── DaoAuthenticationProvider
│   │       ├── UserDetailsService.loadUserByUsername()
│   │       │   └── SELECT * FROM users WHERE username = ? OR email = ?  [1 DB query]
│   │       │
│   │       ├── BCryptPasswordEncoder.matches(rawPassword, passwordHash)
│   │       │   └── verify password — ném BadCredentialsException nếu sai
│   │       │
│   │       └── user.isEnabled() — false nếu isActive=false hoặc đã soft-delete
│   │
│   └── buildAuthResponse(user)
│       │
│       ├── findByUserIdAndDeletedAtIsNullWithStore(userId)    [DB query — StoreMember + Store]
│       ├── findActiveStoreRolesWithDetails(userId)            [DB query — UserRole + Role + Store]
│       └── findByUserIdAndStoreIsNullAndDeletedAtIsNull(userId) [DB query — global roles cho JWT]
│           │
│           └── jwtService.generateToken(user, {userId, roles})
│               └── JWT payload: { sub, userId, roles: ["SUPER_ADMIN"?], iat, exp }
│
└── Response: AuthResponse { accessToken, tokenType, expiresIn, user, storeMemberships }
```

---

## 3. Luồng Request có JWT (`GET /api/stores/{storeId}`)

> Đây là luồng chính của mọi request sau khi đăng nhập.
> **JwtAuthFilter không gọi DB** — mọi thứ lấy từ JWT claims.

```
HTTP GET /api/stores/1
Authorization: Bearer eyJhbGci...
│
├── Tomcat nhận request
│
├── DelegatingFilterProxy → SecurityFilterChain
│
├── JwtAuthFilter                                              [0 DB call]
│   │
│   ├── Đọc header "Authorization: Bearer <token>"
│   ├── jwtService.isTokenValid(token)
│   │   └── verify chữ ký HMAC + kiểm tra exp — không gọi DB
│   │
│   ├── Extract từ JWT claims:
│   │   ├── username  ← claim "sub"
│   │   ├── userId    ← claim "userId"
│   │   └── roles     ← claim "roles" (VD: ["SUPER_ADMIN"])
│   │
│   ├── Build UserPrincipal(userId, username)
│   ├── Build authorities: roles → ["ROLE_SUPER_ADMIN", ...]
│   └── SecurityContextHolder.set( Authentication{principal, authorities} )
│
├── AuthorizationFilter
│   └── request đã authenticated → cho qua
│
├── DispatcherServlet → StoreController.getStore()
│
├── @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
│   │
│   └── StoreAccessEvaluator.isMember(storeId, authentication)
│       │
│       ├── SUPER_ADMIN? → true ngay (bypass cache + DB)
│       │
│       ├── Đọc Redis key "store:role:{userId}:{storeId}"
│       │   ├── Cache HIT  → parse RoleName → kiểm tra trong [OWNER, MANAGER, STAFF]
│       │   │                                                              [0 DB call]
│       │   └── Cache MISS → findActiveStoreRole(userId, storeId)         [1 DB call]
│       │                    └── Ghi Redis với TTL 300s
│       │                    └── kiểm tra trong [OWNER, MANAGER, STAFF]
│       │
│       └── false → 403 Forbidden (ExceptionTranslationFilter trả về)
│
├── StoreService.getStore(storeId, currentUser)
│   └── findById(storeId)  [1 DB call]
│
├── Response: StoreResponse { id, name, ... }
│
└── SecurityContextHolder.clearContext()
    └── xóa Authentication sau mỗi request — stateless, không giữ session
```

---

## 4. So sánh DB calls

| Luồng | DB calls |
|:---|:---|
| Login / Register | ~3 queries (auth + build response) |
| Request thông thường — auth | **0** (JWT claims) |
| Request thông thường — store check, cache hit | **0** (Redis) |
| Request thông thường — store check, cache miss | **1** (DB → cache Redis) |

---

## 5. Quan hệ giữa hai vòng đời

```
┌──────────────────────────────────────────┐
│           APPLICATION LIFECYCLE          │
├──────────────────────────────────────────┤
│                                          │
│  Singleton Beans — sống toàn ứng dụng   │
│  ├── SecurityFilterChain                 │
│  ├── JwtAuthFilter                       │
│  ├── JwtService                          │
│  ├── StoreAccessEvaluator                │
│  └── Controllers / Services             │
│                                          │
└──────────────────────────────────────────┘
                   │
                   │  xử lý từng request
                   ↓
┌──────────────────────────────────────────┐
│          HTTP REQUEST LIFECYCLE          │
├──────────────────────────────────────────┤
│                                          │
│  Request đến                             │
│      ↓                                   │
│  JwtAuthFilter (0 DB)                    │
│      ↓                                   │
│  SecurityContext được set                │
│      ↓                                   │
│  AuthorizationFilter                     │
│      ↓                                   │
│  @PreAuthorize → StoreAccessEvaluator    │
│      ↓                                   │
│  Controller → Service → DB               │
│      ↓                                   │
│  Response                                │
│      ↓                                   │
│  SecurityContext bị clear                │
│                                          │
└──────────────────────────────────────────┘
```
