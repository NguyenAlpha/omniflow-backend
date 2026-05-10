# Store Member & Role Lifecycle

Mô tả vòng đời của một thành viên store — từ lúc store được tạo đến khi member bị xóa —
bao gồm các entity liên quan, ràng buộc nghiệp vụ, và cache invalidation.

---

## 1. Hai entity song hành: StoreMember và UserRole

Mỗi thành viên của store được đại diện bởi **2 entity tồn tại song song**:

| Entity | Lưu gì | Source of truth cho |
|:---|:---|:---|
| `StoreMember` | `positionTitle`, `joinedDate`, `isActive`, sync fields | Metadata hiển thị (chức danh, ngày vào) |
| `UserRole` | `role` (OWNER/MANAGER/STAFF), `isActive` | **Phân quyền** — ai được làm gì trong store |

```
users ──┐
        ├──► store_members  (metadata)
        └──► user_roles     (authorization)  ──► roles
stores ─┘
```

> **Quy tắc:** Mọi thao tác thay đổi membership đều phải update **cả hai** trong cùng
> `@Transactional`. Không được để hai entity lệch nhau.

---

## 2. Vòng đời đầy đủ

```
createStore
    │
    ▼
[OWNER] ──────────────────────────────────────────────────────────►  removeMember
    │                                                                  (không được xóa OWNER)
    │
    ├── addMember(MANAGER) ──► updateMember ──► removeMember
    │
    └── addMember(STAFF)   ──► updateMember ──► removeMember
```

---

## 3. Luồng chi tiết từng thao tác

### 3a. createStore — Tạo store, tự động gán OWNER

```
StoreService.createStore(request, currentUser)
    │
    ├── [TX BEGIN]
    │
    ├── INSERT stores (name, address, phone, email)
    │
    ├── INSERT store_members
    │   └── user = currentUser, store = store, isActive = true, publicId = UUID
    │
    ├── INSERT user_roles
    │   └── user = currentUser, role = OWNER, store = store, isActive = true
    │
    ├── [TX COMMIT]
    │
    └── return StoreResponse

Lưu ý:
- Không cần evictCache vì user chưa có cache entry cho store này
- userRepository.getReferenceById(userId) — không SELECT, chỉ tạo JPA proxy cho FK
```

### 3b. addMember — Thêm thành viên mới

```
StoreService.addMember(storeId, request, currentUser)    [yêu cầu: currentUser là OWNER]
    │
    ├── Kiểm tra: findActiveStoreRole(request.userId, storeId).isPresent() ?
    │   └── true → throw IllegalArgumentException("User is already a member")
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
    ├── evictStoreRoleCache(request.userId, storeId)
    │   └── xóa Redis key "store:role:{userId}:{storeId}" nếu có entry cũ
    │       (VD: user đã từng là member, bị xóa, nay được thêm lại với role khác)
    │
    └── return StoreMemberResponse
```

### 3c. updateMember — Đổi role hoặc trạng thái

```
StoreService.updateMember(storeId, memberId, request, currentUser)    [yêu cầu: OWNER]
    │
    ├── findById(memberId) → StoreMember
    ├── findActiveStoreRole(member.userId, storeId) → UserRole
    │
    ├── Guard: UserRole.role == OWNER AND member.userId ≠ currentUser.userId ?
    │   └── true → throw ForbiddenException("Cannot modify another OWNER")
    │   (OWNER chỉ có thể tự thay đổi role của mình, không thay đổi OWNER khác)
    │
    ├── [TX BEGIN]
    │
    ├── UPDATE user_roles SET role = request.role, isActive = request.isActive
    ├── UPDATE store_members SET positionTitle = request.positionTitle, isActive = request.isActive
    │
    ├── [TX COMMIT]
    │
    ├── evictStoreRoleCache(member.userId, storeId)
    │   └── bắt buộc — role đã thay đổi, cache cũ không còn đúng
    │
    └── return StoreMemberResponse
```

### 3d. removeMember — Xóa thành viên (soft delete)

```
StoreService.removeMember(storeId, memberId, currentUser)    [yêu cầu: OWNER]
    │
    ├── findById(memberId) → StoreMember
    ├── findActiveStoreRole(member.userId, storeId) → UserRole (nullable)
    │
    ├── Guard: UserRole.role == OWNER ?
    │   └── true → throw ForbiddenException("Cannot remove the OWNER from store")
    │   (Store phải luôn có ít nhất 1 OWNER)
    │
    ├── [TX BEGIN]
    │
    ├── UPDATE store_members SET deleted_at = now()
    ├── UPDATE user_roles  SET deleted_at = now()   (nếu UserRole tồn tại)
    │
    ├── [TX COMMIT]
    │
    ├── evictStoreRoleCache(member.userId, storeId)
    │   └── bắt buộc — user không còn là member, cache phải xóa ngay
    │
    └── (void)

Soft delete: deleted_at được set, bản ghi vẫn còn trong DB.
Mọi query tìm member active đều filter AND deleted_at IS NULL.
```

---

## 4. Ràng buộc nghiệp vụ

| Ràng buộc | Được kiểm tra ở đâu |
|:---|:---|
| Chỉ OWNER mới được thêm / sửa / xóa member | `@PreAuthorize("@storeAccess.isOwner(...)")` |
| Không thêm user đã là member | `StoreService.addMember` — check `findActiveStoreRole` |
| Không xóa OWNER | `StoreService.removeMember` — check role trước khi soft delete |
| Không sửa OWNER khác | `StoreService.updateMember` — check `userId ≠ currentUser` |
| Store luôn có ít nhất 1 OWNER | Ràng buộc ngầm — không có API `transferOwnership`, OWNER tự xóa bị chặn |

---

## 5. Cache invalidation — khi nào và tại sao

Redis lưu role của user trong store theo key `store:role:{userId}:{storeId}`.
Cache phải được xóa ngay sau khi DB commit để lần check tiếp theo phản ánh đúng role mới.

```
Thao tác          Evict cần thiết?   Lý do
──────────────────────────────────────────────────────────────────
addMember         Có                 User có thể đã có cache cũ (từ lần member trước)
updateMember      Có                 Role đổi → cache cũ sai
removeMember      Có                 User không còn là member → cache phải xóa
createStore       Không              User chưa có entry cache cho store mới
getStore          Không              Read-only
getMembers        Không              Read-only
```

> **Thứ tự bắt buộc:** evict phải gọi **sau** `@Transactional` commit.
> Nếu gọi trước, request tiếp theo sẽ cache miss → đọc DB → thấy data cũ chưa commit.
> Trong Spring, `@Transactional` commit khi method return — evict ở cuối method là đúng thứ tự.

---

## 6. getMembers — cách query tránh N+1

```
StoreService.getMembers(storeId, currentUser)
    │
    ├── findByStoreIdAndIsActiveAndDeletedAtIsNull(storeId, true)
    │   └── trả về List<StoreMember>  [1 query]
    │
    ├── findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(storeId)
    │   └── trả về List<UserRole>  [1 query]
    │   └── collect thành Map<userId, UserRole>
    │
    └── join in-memory: members.stream().map(m -> toMemberResponse(m, roleMap.get(m.user.id)))
        └── tổng: 2 query, không có vòng lặp gọi DB
```
