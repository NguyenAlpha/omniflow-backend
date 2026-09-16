# Vòng đời hệ thống QuikTech POS

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
│   ├── BusinessAccessEvaluator  — kiểm tra business-scoped role (Redis + DB, catalog endpoints)
│   ├── StoreAccessEvaluator     — kiểm tra store-scoped role (Redis + DB, transactional endpoints)
│   ├── UserDetailsService       — load User từ DB (chỉ dùng khi login)
│   ├── AuthenticationManager    — Spring tự tạo DaoAuthenticationProvider nội bộ (BCrypt verify)
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
│       ├── findActiveBusinessRolesForUser(userId)                        [DB — UserRole + Business (OWNER entries)]
│       ├── findActiveStoreRolesWithBusinessDetails(userId)               [DB — UserRole + Store + Business (MANAGER/STAFF)]
│       └── findByUserIdAndBusinessIsNullAndStoreIsNullAndDeletedAtIsNull(userId) [DB — global roles]
│           │
│           └── jwtService.generateToken(user, {userId, roles})
│               └── JWT payload: { sub, userId, roles: ["SUPER_ADMIN"?], iat, exp }
│
└── Response: AuthResponse { accessToken, tokenType, expiresIn, user, memberships }

    Lưu ý: memberships nhóm theo business — mỗi entry: { businessId, businessName, stores[] }.
    OWNER: stores = tất cả stores trong business (role=OWNER, positionTitle=null).
    MANAGER/STAFF: stores = chỉ stores mà user là member (kèm positionTitle).
    Quyền truy cập catalog được check tại request time qua BusinessAccessEvaluator (không cần JWT).
```

---

## 3. Luồng Request có JWT

### 3a. Transactional endpoint (`GET /api/stores/{storeId}`)

> **BearerTokenAuthenticationFilter không gọi DB** — mọi thứ lấy từ JWT claims.

```
HTTP GET /api/stores/1
Authorization: Bearer eyJhbGci...
│
├── BearerTokenAuthenticationFilter                                        [0 DB call]
│   ├── NimbusJwtDecoder.decode(token) — verify chữ ký + exp
│   └── UserPrincipalConverter.convert(jwt) → UserPrincipal → SecurityContext
│
├── DispatcherServlet → StoreController.getStore()
│
├── @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
│   │
│   └── StoreAccessEvaluator.isMember(storeId, authentication)
│       │
│       ├── SUPER_ADMIN? → true ngay (từ JWT authorities)                  [0 DB, 0 Redis]
│       │
│       ├── resolveBusinessId(storeId)
│       │   ├── Redis HIT "store:business:{storeId}" → businessId          [0 DB, 1 Redis]
│       │   └── Redis MISS → findBusinessIdByStoreId(storeId) → cache      [1 DB, 1 write]
│       │
│       ├── isOwnerWithCache(userId, businessId)
│       │   ├── Redis HIT "business:role:{userId}:{businessId}" → true     [0 DB, 1 Redis]
│       │   └── Redis MISS → findActiveBusinessRole (DB) → cache if OWNER  [1 DB, 1 write]
│       │       └── OWNER? → true ngay
│       │
│       ├── Redis HIT "store:role:{userId}:{storeId}" → MANAGER/STAFF?    [0 DB, 1 Redis]
│       │   └── role trong [MANAGER, STAFF]? → true / false
│       │
│       └── Redis MISS → findActiveStoreRole(userId, storeId) → cache      [1 DB, 1 write]
│                        └── role trong [MANAGER, STAFF]? → true / false
│
├── StoreService.getStore(storeId)
│   └── findById(storeId)                                                  [1 DB]
│
└── Response: StoreResponse { id, name, ... }
```

### 3b. Catalog endpoint (`GET /api/businesses/{businessId}/products`)

```
HTTP GET /api/businesses/5/products
Authorization: Bearer eyJhbGci...
│
├── BearerTokenAuthenticationFilter                                        [0 DB call]
│   └── (giống trên — UserPrincipal → SecurityContext)
│
├── DispatcherServlet → ProductController.list()
│
├── @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
│   │
│   └── BusinessAccessEvaluator.isMember(businessId, authentication)
│       │
│       ├── SUPER_ADMIN? → true ngay                                       [0 DB]
│       │
│       ├── isOwnerWithCache(userId, businessId)
│       │   ├── Redis HIT "business:role:{userId}:{businessId}" → true     [0 DB, 1 Redis]
│       │   └── Redis MISS → findActiveBusinessRole (DB) → cache if OWNER  [1 DB, 1 write]
│       │       └── OWNER? → true ngay
│       │
│       └── resolveBusinessMemberRoleWithCache(userId, businessId)
│           ├── Redis HIT "business:member:{userId}:{businessId}" → role   [0 DB, 1 Redis]
│           └── Redis MISS → findActiveStoreRolesInBusiness (DB) → cache   [1 DB, 1 write]
│               └── có MANAGER hoặc STAFF trong business? → true / false
│
├── ProductService.list(businessId, ...)
│   └── findAllByBusinessId(businessId)                                    [1 DB]
│
└── Response: List<ProductResponse>
```

---

## 4. So sánh DB calls

| Luồng                                                                  | DB calls                                        |
|:-----------------------------------------------------------------------|:------------------------------------------------|
| Login / Register                                                       | ~3 queries (auth + build response)              |
| Request — JWT auth                                                     | **0** (JWT claims)                              |
| Store check — SUPER_ADMIN                                              | **0** (JWT authorities)                         |
| Store check — OWNER (store:business + business:role hit)               | **0** (cả hai Redis hit)                        |
| Store check — OWNER (một trong hai cache miss)                         | **1** (1 DB cho cache miss)                     |
| Store check — OWNER (cả hai cache miss)                                | **2** (findBusinessId + findActiveBusinessRole) |
| Store check — MANAGER/STAFF, store:role hit                            | **1** (isOwnerWithCache → DB, không cache âm)  |
| Store check — MANAGER/STAFF, store:role miss                           | **2** (isOwnerWithCache + findActiveStoreRole)  |
| Business check — SUPER_ADMIN                                           | **0**                                           |
| Business check — OWNER, Redis hit                                      | **0** (Redis cache)                             |
| Business check — OWNER, Redis miss                                     | **1** (findActiveBusinessRole)                  |
| Business check — MANAGER/STAFF, Redis hit                              | **0** (business:member cache)                   |
| Business check — MANAGER/STAFF, Redis miss                             | **1** (findActiveStoreRolesInBusiness)          |

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
│  ├── BusinessAccessEvaluator             │
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
│  @PreAuthorize                           │
│      ├── @businessAccess (catalog)       │
│      └── @storeAccess (transactional)    │
│      ↓                                   │
│  Controller → Service → DB               │
│      ↓                                   │
│  Response                                │
│      ↓                                   │
│  SecurityContext bị clear                │
│                                          │
└──────────────────────────────────────────┘
```
