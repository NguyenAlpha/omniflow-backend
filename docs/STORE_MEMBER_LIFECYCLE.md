# Store Member & Role Lifecycle

Mô tả vòng đời của một thành viên store — từ lúc store được tạo đến khi member bị xóa —
bao gồm các entity liên quan, ràng buộc nghiệp vụ, và cache invalidation.

---

## 1. Hai entity song hành: StoreMember và UserRole

Mỗi thành viên của store được đại diện bởi **2 entity tồn tại song song**:

| Entity | Lưu gì | Source of truth cho |
|:---|:---|:---|
| `StoreMember` | `positionTitle`, `joinedDate`, `isActive`, sync fields | Metadata hiển thị (chức danh, ngày vào) |
| `UserRole` | `role` (MANAGER/STAFF), `isActive` | **Phân quyền** — ai được làm gì trong store |

```
users ──┐
        ├──► store_members  (metadata)
        └──► user_roles     (authorization, store_id SET)  ──► roles
stores ─┘
```

> **Lưu ý:** OWNER không có record trong `store_members` hay `user_roles` ở store level.
> OWNER được quản lý ở business level (`user_roles.business_id SET, store_id = NULL`)
> và có quyền truy cập mọi store trong business một cách tự động.
>
> **Quy tắc:** Mọi thao tác thay đổi membership đều phải update **cả hai** trong cùng
> `@Transactional`. Không được để hai entity lệch nhau.

---

## 2. Vòng đời đầy đủ

```
createStore  (user phải là OWNER của business)
    │
    ▼
[Store tồn tại — OWNER implicit từ business ownership]
    │
    ├── addMember(MANAGER) ──► updateMember ──► removeMember
    │
    └── addMember(STAFF)   ──► updateMember ──► removeMember
```

---

## 3. Luồng chi tiết từng thao tác

### 3a. createStore — Tạo store mới trong business

```
StoreService.createStore(businessId, request, currentUser)
    [yêu cầu: currentUser là OWNER của business — @businessAccess.isOwner(businessId)]
    │
    ├── [TX BEGIN]
    │
    ├── INSERT stores (business_id, name, address, phone, email)
    │
    ├── [TX COMMIT]
    │
    └── return StoreResponse

Lưu ý:
- Không INSERT store_members hay user_roles — OWNER không có store-level membership.
  Quyền của OWNER trên store mới là implicit từ business ownership đã có sẵn.
- Không cần evictCache — không có cache entry nào thay đổi khi tạo store mới.
- userRepository.getReferenceById(userId) — không SELECT, chỉ tạo JPA proxy cho FK.
```

### 3b. addMember — Thêm thành viên mới

```
StoreService.addMember(storeId, request, currentUser)    [yêu cầu: currentUser là OWNER]
    │
    ├── Validate: request.role ∈ {ROLE_MANAGER, ROLE_STAFF}
    │   └── sai → throw IllegalArgumentException (store-scoped chỉ có 2 role này)
    │
    ├── Kiểm tra: findActiveStoreMember(storeId, request.userId).isPresent() ?
    │   └── true → throw IllegalArgumentException("User is already a member")
    │   └── check qua StoreMember (chỉ filter deleted_at) — KHÔNG phụ thuộc isActive,
    │       member đang inactive vẫn tính là "đã là member"
    │
    ├── [TX BEGIN]
    │
    ├── INSERT store_members
    │   └── user = targetUser, positionTitle, isActive = request.isActive
    │
    ├── INSERT user_roles
    │   └── user = targetUser, role = request.role, store, isActive = request.isActive
    │
    ├── [TX COMMIT]
    │
    ├── (afterCommit) evictStoreRoleCache(request.userId, storeId)
    │   └── xóa Redis key "store:role:{userId}:{storeId}" nếu có entry cũ
    │       (VD: user đã từng là member, bị xóa, nay được thêm lại với role khác)
    ├── (afterCommit) evictBusinessMemberCache(request.userId, businessId)
    │
    └── return StoreMemberResponse
```

### 3c. updateMember — Đổi role hoặc trạng thái

```
StoreService.updateMember(storeId, memberId, request, currentUser)    [yêu cầu: OWNER]
    │
    ├── Validate: request.role ∈ {ROLE_MANAGER, ROLE_STAFF}
    │
    ├── findByIdAndStoreIdAndDeletedAtIsNull(memberId, storeId) → StoreMember
    │   └── scoped theo storeId — chống IDOR: memberId thuộc store/business khác → 404
    │       (@PreAuthorize chỉ xác nhận caller là OWNER của store TRONG URL)
    ├── findActiveStoreRole(member.userId, storeId) → UserRole
    │
    ├── [TX BEGIN]
    │
    ├── UPDATE user_roles SET role = request.role, isActive = request.isActive
    ├── UPDATE store_members SET positionTitle = request.positionTitle, isActive = request.isActive
    │
    ├── [TX COMMIT]
    │
    ├── (afterCommit) evictStoreRoleCache(member.userId, storeId)
    │   └── bắt buộc — role đã thay đổi, cache cũ không còn đúng
    │
    ├── (afterCommit) evictBusinessMemberCache(member.userId, businessId)
    │   └── bắt buộc — role cao nhất trong business có thể thay đổi (VD: MANAGER → STAFF)
    │
    └── return StoreMemberResponse

Lưu ý: không cần guard "Cannot modify OWNER" — OWNER không có store_members record,
không thể tìm thấy qua findById(memberId), nên không thể bị updateMember.
```

### 3d. removeMember — Xóa thành viên (soft delete)

```
StoreService.removeMember(storeId, memberId, currentUser)    [yêu cầu: OWNER]
    │
    ├── findByIdAndStoreIdAndDeletedAtIsNull(memberId, storeId) → StoreMember
    │   └── scoped theo storeId — chống IDOR: memberId thuộc store/business khác → 404
    ├── findActiveStoreRole(member.userId, storeId) → UserRole (nullable)
    │
    ├── [TX BEGIN]
    │
    ├── UPDATE store_members SET deleted_at = now()
    ├── UPDATE user_roles  SET deleted_at = now()   (nếu UserRole tồn tại)
    │
    ├── [TX COMMIT]
    │
    ├── (afterCommit) evictStoreRoleCache(member.userId, storeId)
    │   └── bắt buộc — user không còn là member, cache phải xóa ngay
    │
    ├── (afterCommit) evictBusinessMemberCache(member.userId, businessId)
    │   └── bắt buộc — user có thể mất quyền truy cập catalog của business
    │
    └── (void)

Soft delete: deleted_at được set, bản ghi vẫn còn trong DB.
Mọi query tìm member active đều filter AND deleted_at IS NULL.

Lưu ý: không cần guard "Cannot remove OWNER" — OWNER không có store_members record,
không thể tìm thấy qua findById(memberId), nên không thể bị removeMember.
```

---

## 4. Ràng buộc nghiệp vụ

| Ràng buộc | Được kiểm tra ở đâu |
|:---|:---|
| Chỉ OWNER mới được thêm / sửa / xóa member | `@PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")` |
| Member phải thuộc đúng store trong URL (chống IDOR) | `findByIdAndStoreIdAndDeletedAtIsNull(memberId, storeId)` → 404 nếu lệch |
| Không thêm user đã là member (kể cả inactive) | `StoreService.addMember` — check `findActiveStoreMember`; backstop DB: `ux_store_members_user_store` |
| Store-scoped role chỉ có MANAGER / STAFF | `StoreService.validateStoreMemberRole` (add/update); backstop DB: `ux_user_roles_user_store` (V8) chặn 2 role cùng store |
| OWNER không thể bị removeMember / updateMember | Tự nhiên — OWNER không có `store_members` record |
| Business luôn có OWNER | Quản lý ở business level (`user_roles.business_id SET, store_id NULL`) |

---

## 5. Cache invalidation — khi nào và tại sao

Hai cache key bị ảnh hưởng khi store membership thay đổi:
- `store:role:{userId}:{storeId}` — role của user trong store cụ thể
- `business:member:{userId}:{businessId}` — role cao nhất của user trong business

```
Thao tác       store:role   business:member   Lý do
─────────────────────────────────────────────────────────────────────────────
addMember      Có           Có                Có thể có cache cũ; business member role thay đổi
updateMember   Có           Có                Role đổi → cả hai cache cũ đều sai
removeMember   Có           Có                User mất quyền ở store và có thể mất quyền catalog
createStore    Không        Không             Không có cache entry nào thay đổi
getStore       Không        Không             Read-only
getMembers     Không        Không             Read-only
```

> **Thứ tự bắt buộc:** evict phải gọi **sau** `@Transactional` commit.
> Nếu gọi trước, request khác chen vào giữa (sau evict, trước commit) sẽ cache miss
> → đọc DB thấy role CŨ chưa commit → ghi lại vào Redis với TTL đầy đủ — quyền vừa gỡ
> "hồi sinh" tới 5 phút.
>
> Lưu ý: `@Transactional` commit **sau khi method return** (proxy bọc ngoài) — gọi evict
> ở cuối thân method vẫn là TRƯỚC commit. Vì vậy `StoreService` đăng ký evict qua
> `TransactionSynchronizationManager.registerSynchronization(afterCommit)` — xem
> `evictMemberCachesAfterCommit`.

---

## 6. getMembers — cách query tránh N+1

```
StoreService.getMembers(storeId, currentUser)
    │
    ├── findByStoreIdAndIsActiveAndDeletedAtIsNull(storeId, true)
    │   └── trả về List<StoreMember>  [1 query]
    │
    ├── findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(storeId)
    │   └── trả về List<UserRole> (chỉ MANAGER/STAFF)  [1 query]
    │   └── collect thành Map<userId, UserRole>
    │
    └── join in-memory: members.stream().map(m -> toMemberResponse(m, roleMap.get(m.user.id)))
        └── tổng: 2 query, không có vòng lặp gọi DB

Lưu ý: OWNER không xuất hiện trong kết quả — họ không có store_members record.
Nếu UI cần hiển thị OWNER, query riêng từ business_members + user_roles (business level).
```
