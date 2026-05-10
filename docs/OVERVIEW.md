# Project OmniFlow — POS & Inventory System

## 1. Tổng quan dự án

**OmniFlow** là hệ thống quản lý bán hàng và kho vận đa tenant (multi-tenant B2B SaaS), tập trung vào tính chính xác dữ liệu tài chính, phân quyền theo vai trò, và khả năng sync offline cho App Mobile.

- **Tên dự án:** OmniFlow
- **Mô hình:** Multi-tenant B2B SaaS — 1 hệ thống phục vụ nhiều chủ cửa hàng, dữ liệu tách biệt hoàn toàn theo `store_id`
- **Trạng thái:** Phase 1 — Core API đang triển khai

---

## 2. Tech Stack

| Thành phần | Công nghệ |
|:---|:---|
| **Backend** | Java 21 / Spring Boot 3.5 |
| **Database** | PostgreSQL 16 |
| **ORM** | Spring Data JPA + Hibernate 6 |
| **Migration** | Flyway — `ddl-auto=validate`, không dùng `create/update` |
| **Auth** | Spring Security 6 + JWT (JJWT 0.12.6) — stateless, Hybrid cache |
| **Cache** | Redis (Spring Data Redis) — store role cache, TTL 5 phút |
| **Mobile** | React Native (Phase 2) — offline-first với SQLite + delta sync |

---

## 3. Cấu trúc Package

- **Group ID:** `com.omniflow`
- **Artifact ID:** `omniflow-backend`
- **Base Package:** `com.omniflow.backend`

```
com.omniflow.backend
├── config/
│   ├── ApplicationConfig.java       — auth beans; UserDetailsService chỉ dùng cho login (BCrypt verify)
│   ├── SecurityConfig.java          — JWT filter chain, method security
│   └── SystemAdminSeeder.java       — seed SUPER_ADMIN khi khởi động (nếu bật)
├── controller/
│   ├── AuthController.java          — POST /api/auth/register, /login
│   ├── StoreController.java         — CRUD store + member management
│   ├── ProductController.java       — CRUD + search products
│   ├── CategoryController.java      — CRUD categories
│   └── UnitController.java          — CRUD units
├── service/
│   ├── AuthService.java             — register, login, build auth response (nhúng global roles vào JWT)
│   ├── StoreService.java            — store CRUD, member add/update/remove + cache invalidation
│   ├── ProductService.java          — product CRUD + search + price history
│   ├── CategoryService.java         — category CRUD
│   └── UnitService.java             — unit CRUD (system + store)
├── security/
│   ├── JwtService.java              — generate/validate JWT, extract claims (username, userId, roles)
│   ├── JwtAuthFilter.java           — 0 DB call: extract UserPrincipal từ JWT, set SecurityContext
│   ├── UserPrincipal.java           — record(userId, username) — principal trong SecurityContext
│   └── StoreAccessEvaluator.java    — @PreAuthorize helper; Redis cache → DB fallback
├── repository/                      — JpaRepository, nhiều query @Query với JOIN FETCH
├── entity/                          — 26 entity (xem mục 4)
│   └── enums/RoleName.java
├── dto/
│   ├── request/   — auth/, store/, catalog/, order/, purchase/, partner/, inventory/, ...
│   └── response/  — auth/, store/, catalog/, order/, purchase/, partner/, common/, sync/, ...
└── exception/
    ├── ForbiddenException.java
    ├── ResourceNotFoundException.java
    └── GlobalExceptionHandler.java
```

---

## 4. Entity Model (26 entities)

### Xác thực & Phân quyền
| Entity | Mô tả |
|:---|:---|
| `User` | Implements `UserDetails`; có `passwordHash`, `isActive`, soft delete |
| `Role` | Backed by enum `RoleName`; mô tả role |
| `UserRole` | RBAC pivot: `store_id IS NULL` → global role; `store_id NOT NULL` → store-scoped |

**RoleName:**
```
SUPER_ADMIN, SUPPORT       — global (store_id IS NULL)
OWNER, MANAGER, STAFF      — store-scoped (store_id IS NOT NULL)
```

### Tenant & Membership
| Entity | Mô tả |
|:---|:---|
| `Store` | Multi-tenant root — mọi dữ liệu nghiệp vụ gắn `store_id` |
| `StoreMember` | Metadata thành viên: `positionTitle`, `joinedDate`, sync fields |
| `Subscription` | Gói: `FREE / BASIC / PRO`; giới hạn staff/product/warehouse/orders |
| `SubscriptionInvoice` | Lịch sử billing — immutable |

### Danh mục & Kho
| Entity | Mô tả |
|:---|:---|
| `Category` | Per-store; unique `(store_id, name)` |
| `Product` | `sku` unique per store; `searchVector` TSVECTOR cho full-text search |
| `Unit` | `store_id IS NULL` = system unit (SUPER_ADMIN quản lý); NOT NULL = store custom |
| `PriceHistory` | Append-only khi giá thay đổi — không xoá |
| `Warehouse` | Multi-warehouse per store |
| `Inventory` | Tồn kho theo `(product, warehouse)` |
| `InventoryTransaction` | Loại: `IN/OUT/TRANSFER/ADJUSTMENT` — immutable |

### Đối tác
| Entity | Mô tả |
|:---|:---|
| `Customer` | `debtBalance` denorm; `searchVector` cho full-text |
| `Supplier` | `debtBalance` denorm |

### Đơn hàng & Mua hàng
| Entity | Mô tả |
|:---|:---|
| `Order` | Bán hàng; `discountType: FIXED/PERCENT`; status: `PENDING/COMPLETED/CANCELLED` |
| `OrderItem` | Line items với `unitPrice` snapshot + discount per item |
| `ReturnOrder` | Liên kết `originalOrderId`; `refundMethod: CASH/BANK_TRANSFER/STORE_CREDIT` |
| `ReturnOrderItem` | Line items của return |
| `PurchaseOrder` | Nhập hàng từ supplier; status: `PENDING/RECEIVED/CANCELLED` |
| `PurchaseOrderItem` | Line items của purchase |
| `Payment` | Thu/chi; CHECK constraint: chỉ thuộc customer hoặc supplier, không cả hai |

### Audit & Sync
| Entity | Mô tả |
|:---|:---|
| `AuditLog` | `old_data / new_data` JSONB; immutable |
| `SyncChangeLog` | Delta sync cho mobile: `(store_id, table, public_id, operation, sync_version)` |

**Sync fields** (có trên mọi entity editable):
`publicId (UUID)`, `syncVersion (BIGINT)`, `lastModifiedAt`, `lastModifiedByUser`, `lastModifiedByDevice`

---

## 5. Xác thực & Phân quyền (Hybrid JWT + Redis)

### Kiến trúc Hybrid

```
Request → JwtAuthFilter (0 DB)
             ↓ extract từ JWT
         UserPrincipal(userId, username)
         + authorities từ JWT roles
             ↓
         SecurityContext
             ↓
     @PreAuthorize → StoreAccessEvaluator
                          ↓
                     Redis cache
                    ↓         ↓
                  hit         miss
                   ↓           ↓
                return      DB query → save Redis (TTL 5 phút) → return
```

### Luồng đăng nhập
1. Client gửi `POST /api/auth/login`
2. `AuthService` xác thực qua `AuthenticationManager` (BCrypt + DB)
3. `buildAuthResponse` trả về:
   - JWT token (TTL 24h, chứa `userId` + `roles` — global roles)
   - `UserSummaryResponse` — thông tin user
   - `List<StoreMemberResponse>` — tất cả store user thuộc về + role tương ứng

### JwtAuthFilter — 0 DB call
`JwtAuthFilter` không gọi `UserDetailsService`. Mọi thông tin cần thiết được extract thẳng từ JWT:
- `username` → từ `sub` claim
- `userId` → từ `userId` claim
- `roles` → từ `roles` claim (List<String>, chỉ global roles)

Principal trong `SecurityContext` là `UserPrincipal(userId, username)` — không phải `User` entity.

### JWT claims
```json
{
  "sub": "username",
  "userId": 123,
  "roles": ["SUPER_ADMIN"],   // chỉ global roles; store-scoped roles KHÔNG nhúng vào token
  "iat": ...,
  "exp": ...
}
```

### Kiểm tra quyền theo store
`StoreAccessEvaluator` (`@Component("storeAccess")`) dùng trong `@PreAuthorize`:
```java
@PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
```

Flow: SUPER_ADMIN bypass → check Redis `store:role:{userId}:{storeId}` → cache hit → return; cache miss → `findActiveStoreRole()` → save Redis (TTL 300s) → return.

Cache invalidation: `StoreAccessEvaluator.evictStoreRoleCache(userId, storeId)` được gọi từ `StoreService` sau mỗi thao tác add/update/remove member.

### Performance
| Scenario | DB calls |
|:---|:---|
| Mọi request (auth) | **0** — extract từ JWT |
| Store authorization — cache hit | **0** — Redis |
| Store authorization — cache miss | **1** — DB, lưu Redis |
| Login / Register | ~3 queries (xác thực + build response) |

### Repository queries tối ưu (không N+1)
```java
findActiveStoreRole(userId, storeId)          // JOIN FETCH ur.role — dùng bởi StoreAccessEvaluator (cache miss)
findActiveStoreRolesWithDetails(userId)       // JOIN FETCH ur.role + ur.store — dùng bởi buildAuthResponse
findByUserIdAndDeletedAtIsNullWithStore(uid)  // JOIN FETCH sm.store — dùng bởi buildAuthResponse
findByUserIdAndStoreIsNullAndDeletedAtIsNull  // global roles — nhúng vào JWT tại login
```

---

## 6. API Endpoints hiện có

### Auth — `/api/auth`
| Method | Path | Access |
|:---|:---|:---|
| POST | `/register` | Public |
| POST | `/login` | Public |

### Store — `/api/stores`
| Method | Path | Access |
|:---|:---|:---|
| POST | `/` | Authenticated |
| GET | `/my` | Authenticated |
| GET | `/{storeId}` | Member |
| PUT | `/{storeId}` | Owner / Manager |
| GET | `/{storeId}/members` | Member |
| POST | `/{storeId}/members` | Owner |
| PUT | `/{storeId}/members/{id}` | Owner |
| DELETE | `/{storeId}/members/{id}` | Owner |

### Product — `/api/stores/{storeId}/products`
| Method | Path | Access |
|:---|:---|:---|
| GET | `/` | Member |
| GET | `/search?q=` | Member |
| GET | `/{publicId}` | Member |
| POST | `/` | Owner / Manager |
| PUT | `/{publicId}` | Owner / Manager |
| DELETE | `/{publicId}` | Owner / Manager |

### Category — `/api/stores/{storeId}/categories`
| Method | Path | Access |
|:---|:---|:---|
| GET | `/` | Member |
| POST | `/` | Owner / Manager |
| PUT | `/{publicId}` | Owner / Manager |
| DELETE | `/{publicId}` | Owner / Manager |

### Unit — `/api/stores/{storeId}/units`
| Method | Path | Access |
|:---|:---|:---|
| GET | `/` | Member |
| POST | `/` | Owner / Manager |
| PUT | `/{publicId}` | Owner / Manager |
| DELETE | `/{publicId}` | Owner / Manager |

---

## 7. Dependencies (pom.xml)

| Dependency | Dùng cho |
|:---|:---|
| `spring-boot-starter-data-jpa` | JPA / Hibernate 6 |
| `spring-boot-starter-security` | Spring Security 6 |
| `spring-boot-starter-data-redis` | Redis client (store role cache) |
| `spring-boot-starter-validation` | Bean Validation (`@Valid`, `@NotNull`) |
| `spring-boot-starter-web` | REST API |
| `flyway-core` + `flyway-database-postgresql` | Schema migration |
| `postgresql` | JDBC driver |
| `jjwt-api/impl/jackson` 0.12.6 | JWT generate/validate |
| `lombok` | Boilerplate reduction |
| `spring-boot-starter-test` + `spring-security-test` | Unit/integration tests |

---

## 8. Lộ trình phát triển

| Giai đoạn | Nội dung | Trạng thái |
|:---|:---|:---|
| **Phase 1** | Core API + DB Schema + Auth + Web Admin | Đang làm |
| **Phase 2** | Order / Purchase / Return / Inventory / Payment APIs | Chưa bắt đầu |
| **Phase 3** | App Mobile (React Native) + Offline sync | Chưa bắt đầu |
| **Phase 4** | Docker + Cloud deployment + Read replica | Chưa bắt đầu |

**Đã xong (Phase 1):** Entity model (26), Repository layer, Hybrid Auth (JWT + Redis), Auth flow, Store + Member management, Product/Category/Unit CRUD.

**Chưa có service/controller:** Order, ReturnOrder, PurchaseOrder, Inventory, Payment, Warehouse, Customer, Supplier, Subscription, AuditLog, Sync.

---

## 9. Ghi chú cho lập trình viên

### Database
- **Migration:** Chỉ dùng Flyway — `src/main/resources/db/migration/V{n}__{mô_tả}.sql`. Không dùng `ddl-auto=create/update`.
- **Soft delete:** Mọi query trên bảng có `deleted_at` phải filter `AND deleted_at IS NULL`. Dùng `@Where` trên entity hoặc đảm bảo repository method có suffix `AndDeletedAtIsNull`.
- **Immutable tables:** `orders`, `purchase_orders`, `payments`, `inventory_transactions`, `price_history`, `audit_logs`, `subscription_invoices` — không xoá, chỉ thay đổi `status`.
- **Units query:** Phải lấy cả system và store units: `WHERE (store_id = :storeId OR store_id IS NULL) AND deleted_at IS NULL`.
- **Sync write contract:** Mỗi mutation phải tăng `sync_version` atomically và ghi 1 dòng vào `sync_change_log` trong cùng transaction.

### `debt_balance` — invariant bắt buộc
`customers.debt_balance` và `suppliers.debt_balance` là denorm — **phải cập nhật trong cùng `@Transactional`** với sự kiện gây ra thay đổi (order COMPLETED, payment recorded, return COMPLETED). Xem chi tiết trong `docs/DATABASE_SCHEMA.md`.

### JPA / Performance
- **N+1:** Quan hệ `Order → items`, `PurchaseOrder → items`, `ReturnOrder → items` đều nguy cơ N+1. Dùng `JOIN FETCH` hoặc `@EntityGraph` — không để lazy load trong vòng lặp.
- **JPA proxy cho FK:** Services dùng `userRepository.getReferenceById(userId)` để set `lastModifiedByUser` / `createdBy` — không tốn SELECT, Hibernate chỉ cần ID cho foreign key.
- **Pagination:** Dùng keyset (cursor-based) thay vì `Page<T>` OFFSET cho bảng lớn (`orders`, `inventory_transactions`).

### Security
- Store-scoped roles (`OWNER`, `MANAGER`, `STAFF`) **không** đưa vào JWT — chúng phụ thuộc context store, kiểm tra qua `StoreAccessEvaluator` với Redis cache.
- `UserPrincipal` (record với `userId` + `username`) là principal trong `SecurityContext` — không phải `User` entity. Controllers nhận `@AuthenticationPrincipal UserPrincipal`.
- Mọi query nghiệp vụ phải filter `store_id` — không để user truy cập data của store khác.
- `SystemAdminSeeder` bật bằng `admin.seed.enabled=true` trong `application.properties` (mặc định `false`).
- **Cache invalidation:** Mọi thao tác thay đổi role (add/update/remove member) phải gọi `StoreAccessEvaluator.evictStoreRoleCache(userId, storeId)` để xóa Redis key.
- **Redis key format:** `store:role:{userId}:{storeId}` — TTL 300 giây (configurable qua `store.role.cache.ttl`).
