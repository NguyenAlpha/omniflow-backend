# Security — Hybrid JWT + Redis RBAC

Mô tả kiến trúc xác thực và phân quyền của OmniFlow.
Xem thêm luồng chi tiết tại [LIFECYCLE.md](LIFECYCLE.md) và [TOKEN_LIFECYCLE.md](TOKEN_LIFECYCLE.md).

---

## 1. Tổng quan kiến trúc

```
Request
  ↓
BearerTokenAuthenticationFilter  — extract Bearer token, NimbusJwtDecoder validate chữ ký
  ↓
UserPrincipalConverter            — convert JWT claims → UserPrincipal vào SecurityContext
  ↓
@PreAuthorize
  ├── @businessAccess → BusinessAccessEvaluator  — catalog endpoints (product, category, unit, ...)
  └── @storeAccess    → StoreAccessEvaluator     — transactional endpoints (order, inventory, ...)
        ↓
        ├── SUPER_ADMIN?      → bypass (từ JWT authorities, 0 DB/Redis)
        ├── Business OWNER?   → isOwnerWithCache (Redis → DB, business:role:{userId}:{businessId})
        ├── Redis hit?        → dùng cached store role (0 DB call)
        └── Redis miss?       → query DB → cache Redis TTL 300s → return
```

---

## 2. Mô hình phân cấp tenant

```
User
 └── Business (1 user có thể sở hữu nhiều business)
       ├── Subscription  (1 subscription per business)
       ├── Store / chi nhánh (1 business có nhiều stores)
       │     └── Warehouse (kho vật lý trong store)
       ├── Catalog dùng chung: Product, Category, Unit, Customer, Supplier
       └── Transactional per-store: Order, Inventory, PurchaseOrder, Payment
```

**Catalog thuộc Business, không thuộc Store.**
Một sản phẩm tạo ở business level — tất cả các chi nhánh đều thấy. Tương tự category, unit, customer, supplier.

**Transactional thuộc Store.**
Order, Inventory... gắn với từng chi nhánh cụ thể.

---

## 3. Phân loại Role

| Role (enum `RoleName`) | Scope           | Lưu ở đâu khi check                             |
|:-----------------------|:----------------|:------------------------------------------------|
| `ROLE_SUPER_ADMIN`     | Global          | JWT claim `roles` → SecurityContext authorities |
| `ROLE_SUPPORT`         | Global          | JWT claim `roles` → SecurityContext authorities |
| `ROLE_OWNER`           | Business-scoped | DB (`user_roles.business_id`) — không vào JWT   |
| `ROLE_MANAGER`         | Store-scoped    | Redis / DB — không vào JWT                      |
| `ROLE_STAFF`           | Store-scoped    | Redis / DB — không vào JWT                      |

**DB schema của user_roles:**
```
business_id  store_id  → Ý nghĩa
    NULL        NULL   → Global (SUPER_ADMIN / SUPPORT)
    SET         NULL   → Business OWNER
    NULL        SET    → Store MANAGER / STAFF
```
Constraint: `CHECK (NOT (business_id IS NOT NULL AND store_id IS NOT NULL))` — không được set cả hai.

**Tại sao OWNER / MANAGER / STAFF không vào JWT:**
User có thể là OWNER ở business A, MANAGER ở store B của business C. Nhúng tất cả vào token
sẽ làm token phình to và không revoke được khi role thay đổi.

---

## 4. BearerTokenAuthenticationFilter + UserPrincipalConverter — 0 DB call

`BearerTokenAuthenticationFilter` không dùng `UserDetailsService`. Mọi thông tin được extract từ JWT:

```
JWT claims
  sub     → username
  userId  → Long
  roles   → List<String> (VD: ["ROLE_SUPER_ADMIN"])   ← chỉ global roles, giá trị enum đầy đủ có prefix ROLE_
```

Luồng xử lý mỗi request:

1. Filter đọc header `Authorization: Bearer <token>`, trích xuất token string.
2. `NimbusJwtDecoder.decode(token)` — verify chữ ký HMAC-SHA256 và kiểm tra `exp`. Ném `JwtException` (→ 401) nếu sai hoặc hết hạn.
3. `UserPrincipalConverter.convert(jwt)` build `Authentication` với:
   - **Principal**: `UserPrincipal(userId, username, roles)` — không phải `User` entity.
     Controllers inject bằng `@AuthenticationPrincipal UserPrincipal currentUser`, dùng để truy cập `userId`, `username`, check global role.
   - **Authorities**: `List<SimpleGrantedAuthority>` map nguyên văn từ claim `roles` (VD: `"ROLE_SUPER_ADMIN"` → `SimpleGrantedAuthority("ROLE_SUPER_ADMIN")`).
     Dùng trong `@PreAuthorize("hasRole('SUPER_ADMIN')")` (hasRole tự thêm prefix `ROLE_` khi so khớp) hoặc `hasAuthority('ROLE_SUPER_ADMIN')`.
   - **Credentials**: đối tượng `Jwt` gốc — có thể lấy thêm claim nếu cần.
4. `SecurityContextHolder.set(authentication)` — các filter/handler phía sau đọc principal từ đây.

---

## 5. Rate limiting — Bucket4j + Redis

Rate limit có hai lớp, dùng chung Redis nên quota vẫn đúng khi chạy nhiều instance backend:

```text
Request
  ↓
CorsFilter (servlet filter, xử lý CORS trước limiter)
  ↓
RateLimitFilter (trước Spring Security)
  ├── login/register/refresh: quota theo IP, chống brute-force
  └── mọi /api/** khác: trần IP rộng, chống flood thô
  ↓
BearerTokenAuthenticationFilter → JWT → UserPrincipal
  ↓
AuthenticatedRateLimitFilter
  ├── quota mặc định theo userId
  └── quota riêng cho import, export, đổi mật khẩu
```

| Policy | Key | Mặc định |
|---|---|---:|
| Login | IP | 10/phút |
| Register | IP | 5/phút |
| Refresh | IP | 20/phút |
| Mọi `/api/**` | IP | 1.200/phút |
| API đã xác thực | `userId` | 300/phút |
| Import sản phẩm | `userId` | 5/10 phút |
| Export file (`GET` và `HEAD` dùng chung quota) | `userId` | 10/10 phút |
| Đổi mật khẩu | `userId` | 5/10 phút |

Khi hết quota, API trả `429` với envelope `RATE_LIMIT_EXCEEDED`, kèm `Retry-After`,
`RateLimit-Limit`, `RateLimit-Remaining`, và `RateLimit-Reset`. Các header này được
expose qua CORS; response theo IP cũng được xử lý CORS trước khi trả về. Client
không nên tự retry các request ghi dữ liệu hoặc export.

`rate-limit.trusted-proxies` chỉ chứa IP kết nối trực tiếp của Nginx/load balancer.
Chỉ trong trường hợp đó mới dùng `X-Forwarded-For`; client gọi thẳng không thể giả IP.
Các biến môi trường có thể override được liệt kê trong [`.env.example`](../.env.example).

Kiểm tra quota có timeout mặc định 200 ms. Nếu Redis lỗi/timeout, limiter fail-open
và bỏ qua kiểm tra trên instance đó trong 5 giây trước khi thử lại. Điều này không
đảm bảo startup khi Redis down hoặc bảo vệ các Redis consumer khác. Production
vẫn nên có giới hạn thô ở reverse proxy/CDN/WAF trước khi request đến Spring.
Prometheus có metric `rate_limit_requests_total` với tags giới hạn `scope`, `policy`,
`outcome` (`allowed`, `blocked`, `error`, `bypassed`); không có userId, IP hoặc URL động.

Khi đổi quota/window phải tăng `RATE_LIMIT_CONFIG_VERSION`; rollback cũng dùng
version mới lớn hơn. Bucket đang tồn tại được cập nhật theo tỷ lệ token còn lại,
instance cũ không downgrade bucket mới. Xem [RATE_LIMITING.md](RATE_LIMITING.md)
cho hướng dẫn deploy và chạy test.

---

## 6. BusinessAccessEvaluator — Catalog endpoints

Bean name `"businessAccess"`, dùng cho mọi endpoint thuộc catalog (product, category, unit, customer, supplier).

```java
// Đọc catalog — business OWNER hoặc MANAGER/STAFF của bất kỳ store trong business
@PreAuthorize("@businessAccess.isMember(#businessId, authentication)")

// Ghi catalog — business OWNER hoặc MANAGER của bất kỳ store trong business
@PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")

// Chỉ OWNER — dùng khi tạo store mới trong business
@PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
```

**Luồng kiểm tra `isMember(businessId)` / `isOwnerOrManager(businessId)`:**
```
SUPER_ADMIN?                              → true (bypass)
isOwnerWithCache(userId, businessId)?
  Redis HIT "business:role:{userId}:{businessId}"   → true (OWNER)           [0 DB, 1 Redis]
  Redis MISS → findActiveBusinessRole(userId, businessId) → cache if OWNER   [1 DB, 1 write]
resolveBusinessMemberRoleWithCache(userId, businessId)?
  Redis HIT "business:member:{userId}:{businessId}" → role → check allowed   [0 DB, 1 Redis]
  Redis MISS → findActiveStoreRolesInBusiness → cache highest role           [1 DB, 1 write]
→ false
```

**Redis cache keys:**
- Business OWNER role: `business:role:{userId}:{businessId}` — TTL từ `store.role.cache.ttl` (mặc định 300s)
- Business store membership: `business:member:{userId}:{businessId}` — TTL 300s, lưu role cao nhất (`ROLE_MANAGER` hoặc `ROLE_STAFF`)

**Cache invalidation:**
- `BusinessAccessEvaluator.evictBusinessRoleCache(userId, businessId)` — sau khi thay đổi OWNER role.
  Hiện chưa có code path nào thu hồi OWNER role nên chưa có điểm gọi; cache chỉ lưu kết quả positive
  nên việc *cấp* OWNER mới có hiệu lực ngay (cache miss → DB), không cần evict.
- `BusinessAccessEvaluator.evictBusinessMemberCache(userId, businessId)` — sau khi thay đổi store membership.
  `StoreService` gọi tại add/update/removeMember, đăng ký chạy **sau khi transaction commit**
  (`TransactionSynchronizationManager.registerSynchronization`) — nếu evict giữa transaction,
  request khác chen vào sẽ cache lại role cũ chưa commit với TTL đầy đủ.

---

## 6. StoreAccessEvaluator — Transactional endpoints

Bean name `"storeAccess"`, dùng cho mọi endpoint thuộc transactional data (order, inventory, warehouse, ...).

```java
// Member của store — business OWNER hoặc MANAGER/STAFF của store đó
@PreAuthorize("@storeAccess.isMember(#storeId, authentication)")

// Quản lý store — business OWNER hoặc MANAGER của store đó
@PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")

// Chỉ OWNER business — addMember, removeMember, setStatus
@PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
```

**Luồng kiểm tra `isMember(storeId)` / `isOwnerOrManager(storeId)`:**
```
SUPER_ADMIN?                              → true (bypass)
resolveBusinessId(storeId)
  Redis HIT "store:business:{storeId}"   → businessId                 [0 DB, 1 Redis]
  Redis MISS → findBusinessIdByStoreId   → cache                      [1 DB, 1 write]
isOwnerWithCache(userId, businessId)?    → true (OWNER)
  Redis HIT "business:role:{userId}:{businessId}" → true              [0 DB, 1 Redis]
  Redis MISS → findActiveBusinessRole (DB) → cache if OWNER           [1 DB, 1 write]
resolveStoreRoleWithCache(userId, storeId)?  → true nếu role trong allowed
  Redis HIT "store:role:{userId}:{storeId}" → role                    [0 DB, 1 Redis]
  Redis MISS → findActiveStoreRole (DB) → cache                       [1 DB, 1 write]
→ false
```

**Luồng kiểm tra `isOwner(storeId)`:**
```
SUPER_ADMIN?                              → true
resolveBusinessId(storeId)               → businessId (cached: store:business:{storeId})
isOwnerWithCache(userId, businessId)?    → true / false
```

**Redis cache keys:**
- Store role: `store:role:{userId}:{storeId}` — TTL từ `store.role.cache.ttl` (mặc định 300s)
- Store → businessId: `store:business:{storeId}` — TTL 300s (businessId không bao giờ thay đổi sau khi set)
- Business OWNER role: `business:role:{userId}:{businessId}` — TTL 300s (shared key với `BusinessAccessEvaluator`, chỉ cache OWNER)

**Cache invalidation:** Gọi `StoreAccessEvaluator.evictStoreRoleCache(userId, storeId)` sau khi add/update/remove member của store —
`StoreService` evict đồng thời cả `business:member` cache, đăng ký chạy sau khi transaction commit (xem mục 5).
Gọi `BusinessAccessEvaluator.evictBusinessRoleCache(userId, businessId)` sau khi thay đổi OWNER role của business (hiện chưa có code path thu hồi OWNER).

---

## 7. Performance

| Scenario | DB calls | Redis calls |
|:---|:---:|:---:|
| Xác thực mỗi request (JWT) | 0 | 0 |
| Business authorization — OWNER, Redis hit | 0 | 1 read |
| Business authorization — OWNER, Redis miss | 1 | 1 read + 1 write |
| Business authorization — MANAGER/STAFF, Redis hit | 0 | 1 read |
| Business authorization — MANAGER/STAFF, Redis miss | 1 | 1 read + 1 write |
| Store authorization — OWNER (store:business + business:role hit) | 0 | 2 read |
| Store authorization — OWNER (một trong hai miss) | 1 | 2 read + 1 write |
| Store authorization — OWNER (cả hai miss) | 2 | 2 read + 2 write |
| Store authorization — MANAGER/STAFF, store:role hit | 1 | 2 read |
| Store authorization — MANAGER/STAFF, store:role miss | 2 | 2 read + 1 write |
| Login / Register | ~3 | 0 |

---

## 8. Conventions bắt buộc

### Multi-tenant isolation

**Catalog data** (product, category, unit, customer, supplier) — filter theo `business_id`:
```java
// Đúng
productRepository.findByBusinessIdAndDeletedAtIsNull(businessId);

// Sai — lộ data cross-business
productRepository.findAll();
```

**Transactional data** (order, inventory, purchase_order) — filter theo `store_id`:
```java
// Đúng
inventoryRepository.findByStoreIdAndDeletedAtIsNull(storeId);

// Sai — lộ data cross-store
inventoryRepository.findAll();
```

### Thêm endpoint mới

**Catalog endpoint** (URL pattern `/api/businesses/{businessId}/...`):
```java
// GET — đọc catalog
@GetMapping
@PreAuthorize("@businessAccess.isMember(#businessId, authentication)")

// POST / PUT / DELETE — ghi catalog
@PostMapping
@PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
```

**Transactional endpoint** (URL pattern `/api/stores/{storeId}/...`):
```java
// GET — chỉ cần là member của store
@GetMapping
@PreAuthorize("@storeAccess.isMember(#storeId, authentication)")

// POST / PUT / DELETE — cần OWNER hoặc MANAGER
@PostMapping
@PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
```

Không duplicate kiểm tra role bên trong service — `@PreAuthorize` đã đủ.

### JPA proxy cho User FK
Khi service cần set `lastModifiedByUser` hay `createdBy`, dùng proxy thay vì SELECT:

```java
// Đúng — không tốn DB query
User userRef = userRepository.getReferenceById(currentUser.userId());
entity.setLastModifiedByUser(userRef);

// Sai — tốn 1 SELECT thừa
User user = userRepository.findById(currentUser.userId()).orElseThrow();
entity.setLastModifiedByUser(user);
```

### SystemAdminSeeder
Bật bằng `admin.seed.enabled=true` trong `application.properties` (mặc định `false`).
Chỉ bật khi cần seed lần đầu — tắt ngay sau đó.

---

## 9. Cấu hình bảo mật (application.properties)

### Biến môi trường bắt buộc khi deploy production

| Property | Env var | Mô tả |
|:---|:---|:---|
| `spring.datasource.password` | `DB_PASSWORD` | Mật khẩu PostgreSQL |
| `jwt.secret` | `JWT_SECRET` | HMAC-SHA256 key (Base64, ≥32 bytes) |
| `admin.seed.password` | `ADMIN_SEED_PASSWORD` | Mật khẩu seed SUPER_ADMIN |

Các property trên có fallback default trong file (dùng cho local dev). **Production phải set env var** — không commit credentials vào repo.
(`application.properties` nằm trong `.gitignore` — file cấu hình local không được track.)

**Validate lúc khởi động:** `ApplicationConfig` fail-fast nếu `jwt.secret` rỗng hoặc key sau
Base64 decode < 32 bytes (yêu cầu tối thiểu của HMAC-SHA256) — app không boot với key yếu/thiếu.

### Actuator endpoints

Chỉ expose `health` và `prometheus` — các endpoint khác (env, beans, mappings) bị tắt:
```
management.endpoints.web.exposure.include=health,prometheus
```

### CORS allowed headers

Chỉ cho phép các header cần thiết (không dùng wildcard `*` cùng `setAllowCredentials(true)`):
```
Authorization, Content-Type, X-Requested-With, Accept, Origin
```

### Admin service: @PreAuthorize bắt buộc

`SubscriptionService.changePlan()` — endpoint admin override plan — có `@PreAuthorize("hasRole('ROLE_SUPER_ADMIN')")`.
Mọi service method dành riêng cho admin phải có annotation này để đảm bảo không bị gọi từ context không đúng quyền.
