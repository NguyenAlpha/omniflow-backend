# Security — Hybrid JWT + Redis RBAC

Mô tả kiến trúc xác thực và phân quyền của OmniFlow.
Xem thêm luồng chi tiết tại [LIFECYCLE.md](LIFECYCLE.md) và [TOKEN_LIFECYCLE.md](TOKEN_LIFECYCLE.md).

---

## 1. Tổng quan kiến trúc

```
Request
  ↓
JwtAuthFilter           — extract UserPrincipal từ JWT claims (0 DB call)
  ↓
SecurityContext         — lưu UserPrincipal + global role authorities
  ↓
@PreAuthorize           — gọi StoreAccessEvaluator để check store-scoped role
  ↓
StoreAccessEvaluator
  ├── SUPER_ADMIN?      → bypass (quyết định từ JWT authorities, 0 DB/Redis)
  ├── Redis hit?        → dùng cached role (0 DB call)
  └── Redis miss?       → query DB → cache Redis TTL 300s → return
```

---

## 2. Phân loại Role

| Role          | Scope        | Lưu ở đâu khi check                             |
|:--------------|:-------------|:------------------------------------------------|
| `SUPER_ADMIN` | Global       | JWT claim `roles` → SecurityContext authorities |
| `SUPPORT`     | Global       | JWT claim `roles` → SecurityContext authorities |
| `OWNER`       | Store-scoped | Redis / DB — không nhúng vào JWT                |
| `MANAGER`     | Store-scoped | Redis / DB — không nhúng vào JWT                |
| `STAFF`       | Store-scoped | Redis / DB — không nhúng vào JWT                |

**Tại sao store-scoped roles không vào JWT:**
User có thể là OWNER ở store A, STAFF ở store B. Nhúng tất cả vào token sẽ làm token phình to
và không revoke được khi role thay đổi. Giải pháp: check Redis/DB theo từng `storeId` khi cần.

---

## 3. JwtAuthFilter — 0 DB call

`JwtAuthFilter` không dùng `UserDetailsService`. Mọi thông tin được extract từ JWT:

```
JWT claims
  sub     → username
  userId  → Long
  roles   → List<String> (VD: ["SUPER_ADMIN"])
```
Tạo `Authentication` dựa trên `UserNamePasswordAuthenticationToken` với:

1) Principal trong `SecurityContext` là `UserPrincipal(userId, username, List roles)` — không phải `User` entity.
Controllers inject bằng `@AuthenticationPrincipal UserPrincipal currentUser`. dùng để truy cập `userId` và `username` và check global role (SUPER_ADMIN, SUPPORT).

2) Authorities là `List<GrantedAuthority>` được map từ claim `roles` (VD: "SUPER_ADMIN" → "ROLE_SUPER_ADMIN").
dùng để phân quyền global (SUPER_ADMIN, SUPPORT). dùng trong `@PreAuthorize("hasRole('SUPER_ADMIN')")` hoặc check thủ công trong code.

3) Request được SetDitails bằng `WebAuthenticationDetailsSource` để có access đến `remoteAddress` và `sessionId` nếu cần.
dùng để log hoặc kiểm tra thêm nếu muốn (VD: chặn login từ IP lạ).

---

## 4. StoreAccessEvaluator — Cách dùng

Bean name `"storeAccess"`, dùng trong `@PreAuthorize` qua SpEL:

```java
// Chỉ member (bất kỳ role nào)
@PreAuthorize("@storeAccess.isMember(#storeId, authentication)")

// Owner hoặc Manager
@PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")

// Chỉ Owner
@PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
```

`@EnableMethodSecurity` trong `SecurityConfig` là điều kiện để `@PreAuthorize` hoạt động.

**Redis cache key:** `store:role:{userId}:{storeId}` — TTL cấu hình qua `store.role.cache.ttl` (mặc định 300s).

**Cache invalidation:** Gọi `StoreAccessEvaluator.evictStoreRoleCache(userId, storeId)` sau mỗi thao tác
thay đổi role (add / update / remove member). Xem chi tiết: [STORE_MEMBER_LIFECYCLE.md](STORE_MEMBER_LIFECYCLE.md).

---

## 5. Performance

| Scenario | DB calls | Redis calls |
|:---|:---:|:---:|
| Xác thực mỗi request (JWT) | 0 | 0 |
| Store authorization — cache hit | 0 | 1 read |
| Store authorization — cache miss | 1 | 1 read + 1 write |
| Login / Register | ~3 | 0 |

---

## 6. Conventions bắt buộc

### Multi-tenant isolation
Mọi query nghiệp vụ **phải** filter `store_id`. Không được để user đọc data của store khác:

```java
// Đúng
productRepository.findByStoreIdAndDeletedAtIsNull(storeId);

// Sai — lộ data cross-store
productRepository.findAll();
```

### Thêm endpoint mới
Mọi endpoint store-scoped phải có `@PreAuthorize` tương ứng:

```java
// GET — chỉ cần là member
@GetMapping("/{storeId}/something")
@PreAuthorize("@storeAccess.isMember(#storeId, authentication)")

// POST / PUT / DELETE — cần OWNER hoặc MANAGER
@PostMapping("/{storeId}/something")
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
