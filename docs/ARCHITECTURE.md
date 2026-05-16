# Architecture

## 1. Cấu trúc Package

- **Group ID:** `com.quiktech`
- **Artifact ID:** `quik-tech`
- **Base Package:** `com.quiktech.backend`

```
com.quiktech.backend
├── config/
│   ├── ApplicationConfig.java       — auth beans; UserDetailsService chỉ dùng cho login
│   ├── SecurityConfig.java          — JWT filter chain, method security (@EnableMethodSecurity)
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
│   └── WarehouseController.java     — CRUD kho hàng
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
│   └── WarehouseService.java        — CRUD kho hàng
│
├── security/
│   ├── JwtService.java              — generate / validate JWT, extract claims
│   ├── UserPrincipalConverter.java  — convert Jwt → UserPrincipal, set SecurityContext (0 DB call)
│   ├── UserPrincipal.java           — record(userId, username, roles) — principal trong SecurityContext
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
    BearerTokenAuthenticationFilter  — validate JWT signature/expiry (Spring built-in, 0 DB call)
    UserPrincipalConverter           — convert Jwt claims → UserPrincipal, set SecurityContext
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
| `spring-boot-starter-oauth2-resource-server` | 3.5.x | JWT validation (Nimbus), BearerTokenAuthenticationFilter |
| `spring-boot-starter-data-jpa` | 3.5.x | JPA / Hibernate 6 |
| `spring-boot-starter-data-redis` | 3.5.x | Redis client (store role cache) |
| `spring-boot-starter-validation` | 3.5.x | Bean Validation (`@Valid`, `@NotBlank`) |
| `flyway-core` + `flyway-database-postgresql` | — | Schema migration |
| `postgresql` | — | JDBC driver (runtime) |
| `lombok` | — | Boilerplate reduction (`@Getter`, `@Builder`...) |
| `spring-boot-starter-test` | 3.5.x | JUnit 5, Mockito |
| `spring-security-test` | 3.5.x | Security test utilities |
