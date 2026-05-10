# Architecture

## 1. Cấu trúc Package

- **Group ID:** `com.omniflow`
- **Artifact ID:** `omniflow-backend`
- **Base Package:** `com.omniflow.backend`

```
com.omniflow.backend
├── config/
│   ├── ApplicationConfig.java       — auth beans; UserDetailsService chỉ dùng cho login
│   ├── SecurityConfig.java          — JWT filter chain, method security (@EnableMethodSecurity)
│   └── SystemAdminSeeder.java       — seed SUPER_ADMIN khi khởi động (bật bằng admin.seed.enabled=true)
│
├── controller/
│   ├── AuthController.java          — POST /api/auth/register, /login
│   ├── StoreController.java         — CRUD store + member management
│   ├── ProductController.java       — CRUD + search products
│   ├── CategoryController.java      — CRUD categories
│   └── UnitController.java          — CRUD units
│
├── service/
│   ├── AuthService.java             — register, login, build auth response
│   ├── StoreService.java            — store CRUD, member management, cache invalidation
│   ├── ProductService.java          — product CRUD + search + price history
│   ├── CategoryService.java         — category CRUD
│   └── UnitService.java             — unit CRUD (system + store-scoped)
│
├── security/
│   ├── JwtService.java              — generate / validate JWT, extract claims
│   ├── JwtAuthFilter.java           — OncePerRequestFilter; 0 DB call; set SecurityContext
│   ├── UserPrincipal.java           — record(userId, username) — principal trong SecurityContext
│   └── StoreAccessEvaluator.java    — @PreAuthorize helper; Redis cache → DB fallback
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
│   │   ├── catalog/
│   │   ├── order/
│   │   ├── purchase/
│   │   ├── partner/
│   │   └── inventory/
│   └── response/
│       ├── auth/
│       ├── store/
│       ├── catalog/
│       ├── order/
│       ├── purchase/
│       ├── partner/
│       ├── common/        — ApiResult, ErrorDetail, ErrorCode, PagedResult
│       └── sync/
│
└── exception/
    ├── ForbiddenException.java
    ├── ResourceNotFoundException.java
    └── GlobalExceptionHandler.java  — @RestControllerAdvice; xem ERROR_LIFECYCLE.md
```

---

## 2. Layer Architecture

```
HTTP Request
    ↓
[ Filter Layer ]
    JwtAuthFilter           — xác thực JWT, set SecurityContext (0 DB call)
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

| Dependency | Version | Dùng cho |
|:---|:---|:---|
| `spring-boot-starter-web` | 3.5.x | REST API, Jackson JSON |
| `spring-boot-starter-security` | 3.5.x | Spring Security 6, filter chain |
| `spring-boot-starter-data-jpa` | 3.5.x | JPA / Hibernate 6 |
| `spring-boot-starter-data-redis` | 3.5.x | Redis client (store role cache) |
| `spring-boot-starter-validation` | 3.5.x | Bean Validation (`@Valid`, `@NotBlank`) |
| `flyway-core` + `flyway-database-postgresql` | — | Schema migration |
| `postgresql` | — | JDBC driver (runtime) |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.12.6 | JWT generate / validate |
| `lombok` | — | Boilerplate reduction (`@Getter`, `@Builder`...) |
| `spring-boot-starter-test` | 3.5.x | JUnit 5, Mockito |
| `spring-security-test` | 3.5.x | Security test utilities |
