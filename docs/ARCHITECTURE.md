# Architecture

## 1. Cấu trúc Package

- **Group ID:** `com.quiktech`
- **Artifact ID:** `quiktech-pos`
- **Base Package:** `com.quiktech.pos`

```
com.quiktech.pos
├── QuikTechPosApplication.java
│
├── config/
│   ├── ApplicationConfig.java       — auth beans; UserDetailsService chỉ dùng cho login; CORS filter
│   ├── SecurityConfig.java          — JWT filter chain, method security (@EnableMethodSecurity)
│   ├── RateLimiterConfig.java       — hạ tầng Bucket4j trên Redis (proxy manager, timeout) cho rate limit
│   ├── SchedulingConfig.java        — @EnableScheduling cho các job @Scheduled
│   ├── SwaggerConfig.java           — OpenAPI + JWT Bearer scheme cho Swagger UI
│   ├── CustomFunctions.java         — đăng ký hàm HQL fts_match (search_vector @@ plainto_tsquery + unaccent)
│   └── seed/                        — seed dữ liệu khi khởi động (SEED_ENABLED)
│       ├── SystemAdminSeeder.java   — tạo SUPER_ADMIN (admin.seed.*)
│       └── *Seeder.java             — dữ liệu demo: user, business, store, kho, catalog, KH/NCC, đơn
│
├── controller/
│   ├── AuthController.java          — POST /api/auth/register, /login, /refresh, /logout
│   ├── UserController.java          — profile, đổi mật khẩu, lookup user (user tự quản lý)
│   ├── BusinessController.java      — CRUD business, tạo business mặc định, subscription của owner
│   ├── BusinessMemberController.java — trợ lý cấp business (BUSINESS_MANAGER)
│   ├── StoreController.java         — CRUD store + member management
│   ├── PlanController.java          — GET /api/plans (bảng giá công khai)
│   ├── ProductController.java       — CRUD + search + import CSV products
│   ├── CategoryController.java      — CRUD categories
│   ├── UnitController.java          — CRUD units
│   ├── OrderController.java         — đơn bán: tạo, hoàn thành, thanh toán, huỷ
│   ├── PurchaseOrderController.java — đơn nhập: tạo, nhận hàng, thanh toán, huỷ
│   ├── ReturnOrderController.java   — đơn trả hàng
│   ├── InventoryController.java     — tồn kho, điều chỉnh / chuyển kho (đơn lẻ + hàng loạt)
│   ├── PaymentController.java       — sổ quỹ thu chi
│   ├── CustomerController.java      — CRUD khách hàng, thu nợ
│   ├── SupplierController.java      — CRUD nhà cung cấp, trả nợ
│   ├── WarehouseController.java     — CRUD kho hàng
│   ├── DashboardController.java     — KPI + biểu đồ của cửa hàng
│   ├── ExportController.java        — xuất Excel / PDF
│   ├── NotificationController.java  — thông báo trong ứng dụng
│   ├── SubscriptionController.java  — /api/admin/subscriptions: duyệt invoice, đổi gói, thống kê (SUPER_ADMIN)
│   ├── AdminOperationsController.java — /api/admin: session, chi tiết business, audit log (SUPER_ADMIN)
│   ├── AdminUserController.java     — quản lý user (SUPER_ADMIN)
│   ├── AdminPlanController.java     — sửa giá / giới hạn gói (SUPER_ADMIN)
│   ├── AdminPaymentAccountController.java — tài khoản nhận tiền + ảnh QR (SUPER_ADMIN)
│   └── AdminTrafficController.java  — GET /api/admin/traffic, /traffic/system — dashboard lưu lượng (SUPER_ADMIN)
│
├── service/
│   ├── AuthService.java             — register, login, refresh, logout
│   ├── AuthResponseAssembler.java   — dựng AuthResponse: memberships, global roles, JWT
│   ├── RefreshTokenService.java     — tạo / rotate / thu hồi refresh token (reuse detection)
│   ├── UserService.java             — profile, đổi mật khẩu, quản lý user (admin)
│   ├── BusinessService.java         — business CRUD, tạo bộ business/store/kho mặc định
│   ├── BusinessMemberService.java   — trợ lý cấp business, cache invalidation
│   ├── StoreService.java            — store CRUD, member management, cache invalidation
│   ├── SubscriptionService.java     — nâng / hạ gói, invoice lifecycle, admin confirm / reject
│   ├── SubscriptionLimitService.java — kiểm tra giới hạn gói trước khi tạo resource
│   ├── SubscriptionPaymentAccountService.java — tài khoản nhận tiền, snapshot vào invoice
│   ├── PlanCatalogService.java      — nguồn duy nhất cho giá và giới hạn gói (subscription_plans)
│   ├── PaymentQrStorage.java        — lưu / đọc ảnh QR tài khoản nhận tiền trên đĩa
│   ├── ProductService.java          — product CRUD + search + import + price history
│   ├── CategoryService.java         — category CRUD
│   ├── UnitService.java             — unit CRUD (system + business units)
│   ├── OrderService.java            — đơn bán, trừ tồn kho khi tạo
│   ├── PurchaseOrderService.java    — đơn nhập, cộng tồn kho khi nhận
│   ├── ReturnOrderService.java      — đơn trả, hoàn tồn kho + hoàn tiền
│   ├── InventoryService.java        — tồn kho, điều chỉnh / chuyển kho
│   ├── PaymentService.java          — sổ quỹ, ghi / xoá payment công nợ
│   ├── CustomerService.java         — CRUD khách hàng, thu nợ
│   ├── SupplierService.java         — CRUD nhà cung cấp, trả nợ
│   ├── WarehouseService.java        — CRUD kho hàng
│   ├── DashboardService.java        — KPI dashboard (đọc materialized view)
│   ├── ExportService.java           — Excel (Apache POI) / PDF (OpenPDF)
│   ├── NotificationService.java     — đọc / đánh dấu đã đọc thông báo
│   ├── EmailService.java            — gửi email async (Spring Mail)
│   ├── AuditService.java            — ghi audit nghiệp vụ async (dùng bởi AuditAspect)
│   ├── AdminAuditService.java       — audit quản trị, ghi đồng bộ trong transaction (MANDATORY)
│   ├── AdminStatsService.java       — thống kê tổng quan cho SUPER_ADMIN
│   ├── ApiTrafficRecorder.java      — cộng dồn lưu lượng trong bộ nhớ, mỗi phút UPSERT vào api_traffic_* (V11)
│   ├── ApiTrafficService.java       — đọc số liệu lưu lượng + tình trạng instance cho dashboard admin
│   └── Job @Scheduled (xem LIFECYCLE.md mục 1):
│       NotificationProjector, DashboardRefreshScheduler, SubscriptionExpiryScheduler,
│       RefreshTokenCleanupScheduler
│
├── annotation/
│   └── Auditable.java               — @Auditable(action, entityType) đánh dấu method cần audit
│
├── aspect/
│   └── AuditAspect.java             — AOP: bắt method @Auditable, gọi AuditService
│
├── security/
│   ├── JwtService.java              — ký JWT, cung cấp thời hạn token (expiresIn)
│   ├── UserPrincipalConverter.java  — convert Jwt → UserPrincipal, set SecurityContext (0 DB call)
│   ├── UserPrincipal.java           — record(userId, username, roles) — principal trong SecurityContext
│   ├── BusinessAccessEvaluator.java — @PreAuthorize helper cho catalog / business endpoints; Redis cache → DB
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
├── repository/                      — JpaRepository; custom @Query với JOIN FETCH; ProductSpec (Specification)
│
├── entity/                          — 31 JPA entities (schema: database/DATABASE_SCHEMA.md)
│   └── enums/                       — RoleName, OrderStatus, SubscriptionPlan, PaymentMethod, ...
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
└── exception/                       — xem ERROR_LIFECYCLE.md
    ├── GlobalExceptionHandler.java  — @RestControllerAdvice
    ├── ResourceNotFoundException.java
    ├── ForbiddenException.java
    ├── InvalidTokenException.java   — refresh token không hợp lệ / hết hạn (401)
    ├── SubscriptionLimitExceededException.java — vượt giới hạn gói (402)
    ├── RateLimitExceededException.java — 429 cho quota kiểm tra trong service (đăng nhập sai)
    └── PaymentAccountUnavailableException.java — chưa có tài khoản nhận tiền (503)
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
