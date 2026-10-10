# Architecture

## 1. Cấu trúc Package

- **Group ID:** `com.quiktech`
- **Artifact ID:** `quiktech-pos`
- **Base Package:** `com.quiktech.pos`

```
com.quiktech.pos
├── config/
│   ├── ApplicationConfig.java       — auth beans; UserDetailsService chỉ dùng cho login
│   ├── SecurityConfig.java          — JWT filter chain, method security (@EnableMethodSecurity)
│   ├── RateLimiterConfig.java       — hạ tầng Bucket4j trên Redis (proxy manager, timeout) cho rate limit
│   └── SystemAdminSeeder.java       — seed SUPER_ADMIN khi khởi động (bật bằng admin.seed.enabled=true)
│
├── controller/
│   ├── AuthController.java          — POST /api/auth/register, /login
│   ├── UserController.java          — profile, đổi mật khẩu (user tự quản lý)
│   ├── AdminUserController.java     — quản lý user (SUPER_ADMIN)
│   ├── StoreController.java         — CRUD store + member management
│   ├── ProductController.java       — CRUD + search products
│   ├── CategoryController.java      — CRUD categories
│   ├── UnitController.java          — CRUD units
│   ├── OrderController.java         — tạo và xem đơn bán
│   ├── PurchaseOrderController.java — tạo và xem đơn nhập
│   ├── ReturnOrderController.java   — tạo và xem đơn trả
│   ├── InventoryController.java     — xem tồn kho, điều chỉnh thủ công
│   ├── PaymentController.java       — ghi nhận thanh toán
│   ├── CustomerController.java      — CRUD khách hàng
│   ├── SupplierController.java      — CRUD nhà cung cấp
│   ├── WarehouseController.java     — CRUD kho hàng
│   └── AdminTrafficController.java  — GET /api/admin/traffic, /traffic/system — dashboard lưu lượng (SUPER_ADMIN)
│
├── service/
│   ├── AuthService.java             — register, login, build auth response
│   ├── UserService.java             — profile, đổi mật khẩu, quản lý user (admin)
│   ├── StoreService.java            — store CRUD, member management, cache invalidation
│   ├── ProductService.java          — product CRUD + search + price history
│   ├── CategoryService.java         — category CRUD
│   ├── UnitService.java             — unit CRUD (system + store-scoped)
│   ├── OrderService.java            — tạo đơn bán, trừ tồn kho
│   ├── PurchaseOrderService.java    — tạo đơn nhập, cộng tồn kho
│   ├── ReturnOrderService.java      — tạo đơn trả, hoàn tồn kho
│   ├── InventoryService.java        — xem tồn kho, điều chỉnh thủ công
│   ├── PaymentService.java          — ghi nhận và tra cứu thanh toán
│   ├── CustomerService.java         — CRUD khách hàng
│   ├── SupplierService.java         — CRUD nhà cung cấp
│   ├── WarehouseService.java        — CRUD kho hàng
│   ├── ApiTrafficRecorder.java      — cộng dồn lưu lượng trong bộ nhớ, mỗi phút UPSERT vào api_traffic_* (V11)
│   └── ApiTrafficService.java       — đọc số liệu lưu lượng + tình trạng instance cho dashboard admin
│
├── security/
│   ├── JwtService.java              — generate / validate JWT, extract claims
│   ├── UserPrincipalConverter.java  — convert Jwt → UserPrincipal, set SecurityContext (0 DB call)
│   ├── UserPrincipal.java           — record(userId, username, roles) — principal trong SecurityContext
│   ├── StoreAccessEvaluator.java    — @PreAuthorize helper; Redis cache → DB fallback
│   ├── LoginAttemptLimiter.java     — giới hạn đăng nhập sai theo tài khoản (gọi từ AuthService.login)
│   └── ClientIpResolver.java        — lấy IP client; chỉ tin X-Forwarded-For từ rate-limit.trusted-proxies
│
├── filter/                          — servlet filter chạy quanh mọi request (thứ tự: xem LIFECYCLE.md mục 1)
│   ├── ApiTrafficFilter.java        — đo mọi /api/** cho dashboard lưu lượng; chạy ngoài cùng
│   ├── RateLimitFilter.java         — quota theo IP, trước Spring Security
│   ├── AuthenticatedRateLimitFilter.java — quota theo userId, sau BearerTokenAuthenticationFilter
│   ├── IdempotencyFilter.java       — chống tạo đơn trùng (header Idempotency-Key)
│   ├── RateLimitService.java        — trừ/xem token trên bucket Redis; fallback bucket cục bộ khi Redis lỗi
│   ├── RateLimitResponseWriter.java — ghi response 429 + header Retry-After / RateLimit-*
│   └── RateLimitMetrics.java        — metric Prometheus rate_limit_requests_total
│
├── repository/                      — JpaRepository; custom @Query với JOIN FETCH
│
├── entity/                          — 26 JPA entities (xem ENTITY_MODEL.md)
│   └── enums/
│       └── RoleName.java
│
├── dto/
│   ├── request/
│   │   ├── auth/
│   │   ├── store/
│   │   └── ...
│   └── response/
│       ├── common/     — ApiResult, ErrorDetail, ErrorCode, PagedResult
│       ├── auth/
│       ├── store/
│       └── ...
│
└── exception/
    ├── ForbiddenException.java
    ├── ResourceNotFoundException.java
    ├── RateLimitExceededException.java — 429 cho quota kiểm tra trong service (đăng nhập sai)
    └── GlobalExceptionHandler.java  — @RestControllerAdvice; xem ERROR_LIFECYCLE.md
```

---

## 2. Layer Architecture

```
HTTP Request
    ↓
[ Filter Layer ]
    CorsFilter                     — CORS trước limiter, expose header 429
        ↓
    RateLimitFilter                — quota IP: auth endpoints + trần /api/**
    BearerTokenAuthenticationFilter  — validate JWT signature/expiry (Spring built-in, 0 DB call)
    UserPrincipalConverter           — convert Jwt claims → UserPrincipal, set SecurityContext
    AuthenticatedRateLimitFilter     — quota userId, import/export/password có policy riêng
    ↓
[ Security Layer ]
    @PreAuthorize           — kiểm tra quyền store-scoped qua StoreAccessEvaluator
    ↓
[ Controller Layer ]
    @RestController         — nhận request, gọi service, trả ResponseEntity<ApiResult<T>>
    ↓
[ Service Layer ]
    @Service @Transactional — business logic, orchestrate repository calls
    ↓
[ Repository Layer ]
    JpaRepository + @Query  — tương tác DB; JOIN FETCH để tránh N+1
    ↓
[ Database ]
    PostgreSQL              — multi-tenant data với store_id isolation
    Redis                   — store role cache (TTL 5 phút)
```

**Quy tắc phân layer:**
- Controller không chứa business logic — chỉ delegate xuống service
- Service không gọi trực tiếp controller khác — dùng service khác nếu cần
- Repository không chứa business logic — chỉ query
- DTO không leak entity ra ngoài controller layer

---

## 3. Dependencies

| Dependency                                   | Version | Dùng cho                                                 |
|:---------------------------------------------|:--------|:---------------------------------------------------------|
| `spring-boot-starter-webmvc`                 | 4.1.x   | REST API, Jackson 3 JSON (`tools.jackson.*`)             |
| `spring-boot-starter-security`               | 4.1.x   | Spring Security 7, filter chain                          |
| `spring-boot-starter-security-oauth2-resource-server` | 4.1.x | JWT validation (Nimbus), BearerTokenAuthenticationFilter |
| `spring-boot-starter-data-jpa`               | 4.1.x   | JPA / Hibernate 7                                        |
| `spring-boot-starter-data-redis`             | 4.1.x   | Redis client (store role cache)                          |
| `spring-boot-starter-validation`             | 4.1.x   | Bean Validation (`@Valid`, `@NotBlank`)                  |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | 4.1.x | Schema migration — Boot 4 chỉ tự chạy Flyway khi có starter này |
| `spring-boot-starter-aspectj`                | 4.1.x   | AOP cho `@Auditable`                                      |
| `springdoc-openapi-starter-webmvc-ui`        | 3.1.x   | Swagger UI / OpenAPI (dòng 3.1 dành cho Boot 4.1)        |
| `postgresql`                                 | —       | JDBC driver (runtime)                                    |
| `lombok`                                     | —       | Boilerplate reduction (`@Getter`, `@Builder`...)         |
| `spring-boot-starter-test`                   | 4.1.x   | JUnit 5, Mockito                                         |
| `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test` | 4.1.x | MockMvc / `@WebMvcTest`, security test utilities |
