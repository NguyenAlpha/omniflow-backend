**Database & Schema**
- Migration `V1__initial_schema.sql` — 24 bảng, indexes, constraints, triggers, materialized views
- Fix `nativeQuery = false` → `true` cho `ProductRepository.fullTextSearch`
- Thêm FK constraint cho `inventory_transactions` → `orders`, `purchase_orders`

**Entity & Repository**
- `User` implement `UserDetails`
- `StoreMember.role` dùng enum `StoreRole` thay vì String
- Thêm các method còn thiếu vào `UserRepository`, `StoreMemberRepository`

**DTO**
- Tạo đủ Response DTO còn thiếu: `WarehouseResponse`, `InventoryResponse`, `InventoryTransactionResponse`, `ReturnOrderResponse`, `ReturnOrderItemResponse`, `PriceHistoryResponse`, `SubscriptionResponse`
- Tạo Request DTO còn thiếu: `WarehouseUpsertRequest`, `ReturnOrderCreateRequest`, `ReturnOrderItemRequest`, `InventoryAdjustRequest`
- Chuẩn hóa response: `ApiResult<T>`, `PagedResult<T>`, `ErrorDetail`, `ErrorCode`

**Security & Auth**
- `JwtService`, `JwtAuthFilter`, `SecurityConfig`, `ApplicationConfig`
- `AuthService`, `AuthController` — register, login
- Fix circular dependency bằng cách tách `ApplicationConfig` ra khỏi `SecurityConfig`
- Config `AuthenticationEntryPoint` trả 401 thay vì 403

**Business Logic**
- `StoreService` + `StoreController` — CRUD store, quản lý member, phân quyền theo `StoreRole`
- `CategoryService` + `CategoryController`
- `UnitService` + `UnitController` — hỗ trợ system unit + store unit
- `ProductService` + `ProductController`
**Exception Handling**
- `ResourceNotFoundException`, `ForbiddenException`
- `GlobalExceptionHandler` — chuyển về `exception` package

**Tests**
- `JwtServiceTest` — 6 cases
- `AuthServiceTest` — 5 cases
- `AuthControllerTest` — 8 cases (bao gồm security test)

---

Còn lại: **Product, Warehouse, Inventory, Order, PurchaseOrder, Payment**.