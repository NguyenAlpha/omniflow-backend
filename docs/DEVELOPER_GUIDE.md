# Developer Guide

Conventions và patterns cần tuân theo khi thêm feature mới vào OmniFlow.

---

## 1. JPA & Query Patterns

### Tránh N+1

Quan hệ `@ManyToOne` và `@OneToMany` mặc định là lazy — không để Hibernate tự load trong vòng lặp:

```java
// Sai — N+1: mỗi order gọi 1 query để load items
orders.forEach(o -> process(o.getItems()));

// Đúng — dùng JOIN FETCH trong repository
@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.store.id = :storeId")
List<Order> findWithItems(@Param("storeId") Long storeId);
```

Các quan hệ có nguy cơ N+1 cao trong Phase 2:
- `Order → OrderItem`
- `PurchaseOrder → PurchaseOrderItem`
- `ReturnOrder → ReturnOrderItem`

### JPA proxy cho foreign key

Khi chỉ cần set FK (không đọc data), dùng `getReferenceById` — không tốn SELECT:

```java
// Đúng — proxy, 0 query
User userRef = userRepository.getReferenceById(currentUser.userId());
product.setLastModifiedByUser(userRef);

// Sai — tốn 1 SELECT thừa
User user = userRepository.findById(currentUser.userId()).orElseThrow();
product.setLastModifiedByUser(user);
```

### Pagination

Dùng keyset (cursor-based) thay vì offset cho bảng lớn:

```java
// Sai cho bảng lớn — OFFSET scan toàn bộ rows trước khi skip
Page<Order> findByStoreId(Long storeId, Pageable pageable); // OFFSET-based

// Đúng — keyset: chỉ đọc từ cursor trở đi
List<Order> findByStoreIdAndIdLessThan(Long storeId, Long cursorId, Pageable pageable);
```

Áp dụng cho: `orders`, `inventory_transactions`, `audit_logs`, `sync_change_log`.

### JOIN FETCH trong buildAuthResponse

`AuthService.buildAuthResponse` dùng 2 query có JOIN FETCH để tránh N+1 khi load store memberships:

```java
storeMemberRepository.findByUserIdAndDeletedAtIsNullWithStore(userId)  // JOIN FETCH sm.store
userRoleRepository.findActiveStoreRolesWithDetails(userId)             // JOIN FETCH ur.role + ur.store
```

Sau đó join in-memory qua `storeId` — không gọi thêm query nào.

---

## 2. Transaction Conventions

### @Transactional đặt ở service, không ở repository

```java
// Đúng
@Service
public class StoreService {
    @Transactional
    public StoreResponse createStore(...) { ... }

    @Transactional(readOnly = true)
    public StoreResponse getStore(...) { ... }
}
```

### readOnly = true cho query

Dùng `@Transactional(readOnly = true)` cho mọi method chỉ đọc —
Hibernate skip dirty checking, giảm overhead:

```java
@Transactional(readOnly = true)
public List<ProductResponse> list(Long storeId, ...) { ... }
```

### Atomicity cho debt_balance

`customers.debt_balance` và `suppliers.debt_balance` là denormalized —
phải cập nhật trong cùng transaction với sự kiện gây ra thay đổi:

```java
@Transactional
public void completeOrder(Long orderId) {
    order.setStatus(COMPLETED);
    orderRepository.save(order);

    // Phải trong cùng transaction này — không được tách ra
    customer.setDebtBalance(customer.getDebtBalance().add(order.getTotalAmount()));
    customerRepository.save(customer);
}
```

### Sync write contract

Mỗi mutation phải ghi `sync_change_log` trong cùng transaction:

```java
@Transactional
public ProductResponse create(...) {
    Product saved = productRepository.save(product);

    // Phải trong cùng transaction
    syncChangeLogRepository.save(SyncChangeLog.of(storeId, "products", saved.getPublicId(), "INSERT", saved.getSyncVersion()));

    return toResponse(saved);
}
```

---

## 3. Soft Delete Convention

Mọi entity xóa được đều có cột `deleted_at`. Khi xóa:

```java
// Đúng — soft delete
entity.setDeletedAt(LocalDateTime.now());
repository.save(entity);

// Sai — hard delete làm mất lịch sử
repository.delete(entity);
```

Mọi repository method tìm entity active **phải** có suffix `AndDeletedAtIsNull`:

```java
// Đúng
List<Product> findByStoreIdAndDeletedAtIsNull(Long storeId);

// Sai — trả về cả bản ghi đã xóa
List<Product> findByStoreId(Long storeId);
```

---

## 4. Thêm Endpoint Mới — Checklist

```
□ Controller method có @PreAuthorize phù hợp
□ @AuthenticationPrincipal UserPrincipal (không phải User entity)
□ Service method nhận UserPrincipal, dùng getReferenceById cho FK
□ Query repository filter store_id (multi-tenant isolation)
□ Query có AND deleted_at IS NULL nếu bảng có soft delete
□ Mutation: cập nhật debt_balance nếu liên quan customer/supplier
□ Mutation: ghi sync_change_log trong cùng transaction
□ Mutation: evictStoreRoleCache nếu thay đổi UserRole
□ Throw đúng exception type (xem ERROR_LIFECYCLE.md)
□ Immutable table không có DELETE/UPDATE nội dung — chỉ UPDATE status
```

---

## 5. Naming Conventions

### Repository methods

| Pattern | Ví dụ |
|:---|:---|
| Active records | `findBy...AndDeletedAtIsNull` |
| With JOIN FETCH | `findBy...With{Relation}` |
| Existence check | `existsBy...` |
| Count | `countBy...` |

### DTO

| Loại | Suffix | Package |
|:---|:---|:---|
| Request body | `Request` | `dto/request/{domain}/` |
| Response body | `Response` | `dto/response/{domain}/` |
| Dùng Java record | — | Immutable by default |

### Exception

```java
// Entity không tìm thấy
throw new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found");

// Không đủ quyền (business rule, không phải Spring Security)
throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot remove the OWNER from store");

// Vi phạm business rule (trùng tên, trùng mã...)
throw new IllegalArgumentException("SKU already exists in this store");
```
