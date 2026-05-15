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
│   ├── JwtService               — ký JWT sau khi đăng nhập / đăng ký
│   ├── UserPrincipalConverter   — convert JWT đã validate → UserPrincipal trong SecurityContext
│   ├── StoreAccessEvaluator     — kiểm tra store-scoped role (Redis + DB)
│   ├── UserDetailsService       — load User từ DB (chỉ dùng khi login)
│   ├── AuthenticationProvider   — DaoAuthenticationProvider (BCrypt verify)
│   ├── AuthenticationManager    — điều phối các AuthenticationProvider
│   ├── JwtDecoder               — NimbusJwtDecoder, validate chữ ký HMAC-SHA256 mỗi request
│   ├── JwtEncoder               — NimbusJwtEncoder, ký token khi đăng nhập
│   ├── Controllers / Services   — xử lý business logic
│   └── StringRedisTemplate      — Redis client
│
├── SecurityFilterChain được build — thứ tự filter cố định cho toàn app
│   │
│   ├── BearerTokenAuthenticationFilter  ← auto-register bởi oauth2ResourceServer().jwt()
│   │                                       extract Bearer token → NimbusJwtDecoder validate
│   │                                       → UserPrincipalConverter → SecurityContext
│   ├── AnonymousAuthenticationFilter    ← set anonymous nếu chưa có auth
│   ├── ExceptionTranslationFilter       ← bắt 401/403, trả JSON error
│   └── AuthorizationFilter              ← kiểm tra quyền truy cập endpoint
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
├── BearerTokenAuthenticationFilter
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
> **BearerTokenAuthenticationFilter không gọi DB** — mọi thứ lấy từ JWT claims.

```
HTTP GET /api/stores/1
Authorization: Bearer eyJhbGci...
│
├── Tomcat nhận request
│
├── DelegatingFilterProxy → SecurityFilterChain
│
├── BearerTokenAuthenticationFilter                            [0 DB call]
│   │
│   ├── Đọc header "Authorization: Bearer <token>"
│   │
│   ├── NimbusJwtDecoder.decode(token)
│   │   └── verify chữ ký HMAC-SHA256 + kiểm tra exp — không gọi DB
│   │       └── ném JwtException nếu chữ ký sai hoặc token hết hạn → 401
│   │
│   ├── UserPrincipalConverter.convert(jwt)
│   │   ├── username  ← jwt.getSubject()         (claim "sub")
│   │   ├── userId    ← jwt.getClaim("userId")   (normalize Integer/Long → Long)
│   │   ├── roles     ← jwt.getClaim("roles")    (VD: ["SUPER_ADMIN"])
│   │   └── Build UserPrincipal(userId, username, roles)
│   │       └── authorities: roles → [SimpleGrantedAuthority("SUPER_ADMIN"), ...]
│   │
│   └── SecurityContextHolder.set( Authentication{principal=UserPrincipal, credentials=jwt} )
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

| Luồng                                          | DB calls                           |
|:-----------------------------------------------|:-----------------------------------|
| Login / Register                               | ~3 queries (auth + build response) |
| Request thông thường — auth                    | **0** (JWT claims)                 |
| Request thông thường — store check, cache hit  | **0** (Redis)                      |
| Request thông thường — store check, cache miss | **1** (DB → cache Redis)           |

---

## 5. Quan hệ giữa hai vòng đời

```
┌──────────────────────────────────────────┐
│           APPLICATION LIFECYCLE          │
├──────────────────────────────────────────┤
│                                          │
│  Singleton Beans — sống toàn ứng dụng   │
│  ├── SecurityFilterChain                 │
│  ├── BearerTokenAuthenticationFilter     │
│  ├── JwtService                          │
│  ├── UserPrincipalConverter              │
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
│  BearerTokenAuthenticationFilter (0 DB)  │
│      ├── NimbusJwtDecoder.decode()       │
│      └── UserPrincipalConverter.convert()│
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
