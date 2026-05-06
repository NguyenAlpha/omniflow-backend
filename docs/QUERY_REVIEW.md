# Đánh giá Query-Friendliness — OmniFlow

> **Vai trò đánh giá:** Database Architect chuyên PostgreSQL + Spring Data JPA
> **Ngày đánh giá:** 2026-05-06
> **Stack:** PostgreSQL · Spring Boot 3.x · Spring Data JPA (Hibernate 6)

---

## 1. Bảng điểm

| Nhóm | Điểm /10 | Vấn đề chính |
|---|---|---|
| 11a. JOIN complexity | 7/10 | Hầu hết query chính ≤ 3 JOIN, nhưng báo cáo công nợ cần 4–5 bảng |
| 11b. N+1 query risk | 5/10 | `orders → order_items → products` và `store → store_members → users` là các điểm N+1 rõ ràng |
| 11c. Filter / sort / pagination | 6/10 | Index composite đã có nhưng chưa cover hết, keyset pagination chưa hỗ trợ tốt |
| 11d. Aggregation & reporting | 4/10 | Không có summary table — báo cáo doanh thu/tồn kho sẽ full scan khi data lớn |
| 11e. JPA anti-patterns | 6/10 | `inventory_transactions.reference_type/id` là polymorphic FK không ràng buộc còn sót lại |

### Điểm trung bình tiêu chí 11: **5.6 / 10**

---

## 2. Vấn đề cụ thể

---

### 11a — JOIN complexity

**Tốt:** Query đơn hàng cơ bản chỉ cần 2–3 JOIN:
```sql
SELECT o.*, c.name, w.name
FROM orders o
LEFT JOIN customers c ON c.id = o.customer_id
JOIN warehouses w ON w.id = o.warehouse_id
WHERE o.store_id = ? AND o.status = 'PENDING';
```

**Vấn đề:** Màn hình báo cáo công nợ tổng hợp cần 5 bảng:
```sql
SELECT c.name, c.debt_balance,
       COUNT(o.id) AS total_orders,
       SUM(p.amount) AS total_paid
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id AND o.store_id = ?
LEFT JOIN payments p ON p.customer_id = c.id AND p.store_id = ?
WHERE c.store_id = ? AND c.deleted_at IS NULL
GROUP BY c.id, c.name, c.debt_balance;
```

Hibernate sẽ sinh nhiều sub-query hoặc cartesian join nếu không viết JPQL cẩn thận.

**Vấn đề 2:** Lấy thông tin nhân viên đầy đủ cần qua 2 bảng:
```sql
SELECT u.full_name, u.email, sm.role, sm.position_title
FROM store_members sm
JOIN users u ON u.id = sm.user_id
WHERE sm.store_id = ? AND sm.deleted_at IS NULL;
```
Không phức tạp nhưng cần nhớ luôn JOIN — nếu lazy load sẽ N+1.

---

### 11b — N+1 query risk

**Điểm nguy hiểm #1 — `Order → OrderItems → Product`**

Nếu load danh sách đơn hàng rồi access `order.getItems()`:
```java
// BUG: N+1 — mỗi order sẽ trigger 1 query lấy items
List<Order> orders = orderRepository.findByStoreId(storeId);
orders.forEach(o -> o.getItems().size()); // N queries
```

**Fix:**
```java
@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.storeId = :storeId")
List<Order> findByStoreIdWithItems(@Param("storeId") Long storeId);
```

---

**Điểm nguy hiểm #2 — `Store → StoreMembers → Users`**

```java
// BUG: Load store → getMembers() → mỗi member lại getUser()
Store store = storeRepository.findById(id).get();
store.getMembers().forEach(m -> m.getUser().getFullName()); // 1 + N + N queries
```

**Fix:**
```java
@EntityGraph(attributePaths = {"members", "members.user"})
Optional<Store> findWithMembersById(Long id);
```

---

**Điểm nguy hiểm #3 — `InventoryTransaction` không có quan hệ ngược**

`inventory_transactions` có `reference_type` + `reference_id` — không thể dùng JPA navigation, phải query thủ công. Không phải N+1 nhưng dễ bị miss index.

---

**Điểm nguy hiểm #4 — `PurchaseOrder → Items → Product`**

Tương tự `Order → OrderItems`, cùng pattern, cùng rủi ro.

---

### 11c — Filter / sort / pagination

**Tốt:** Index composite `(store_id, status)` và `(store_id, created_at DESC)` đã có — cover được query phổ biến nhất.

**Vấn đề #1 — `deleted_at IS NULL` trong WHERE**

Hầu hết query cần filter `deleted_at IS NULL`. Partial index đã khai báo trong schema là đúng hướng, nhưng cần đảm bảo JPA không sinh query bỏ qua condition này:

```java
// Sai — Hibernate có thể không dùng partial index nếu không có WHERE deleted_at IS NULL
List<Product> findByStoreId(Long storeId);

// Đúng — thêm điều kiện để PostgreSQL dùng partial index
List<Product> findByStoreIdAndDeletedAtIsNull(Long storeId);
```

**Vấn đề #2 — OFFSET pagination sẽ chậm với bảng lớn**

`orders` và `inventory_transactions` sẽ có hàng triệu rows. OFFSET-based pagination (`Page<T>`) rất chậm ở trang cuối:

```sql
-- Chậm khi offset lớn — phải đọc và bỏ toàn bộ rows trước
SELECT * FROM orders WHERE store_id = ? ORDER BY created_at DESC LIMIT 20 OFFSET 10000;
```

**Fix — Keyset pagination (cursor-based):**
```sql
-- Nhanh hơn nhiều — dùng index trực tiếp
SELECT * FROM orders
WHERE store_id = ?
  AND (created_at, id) < (:lastCreatedAt, :lastId)
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

Schema hiện tại đã có `id` (BIGSERIAL) và `created_at` — đủ điều kiện làm cursor. Chỉ cần implement ở tầng repository.

**Vấn đề #3 — Thiếu index cho filter theo `deleted_at IS NULL` + `is_active`**

Một số bảng có cả `is_active` và `deleted_at`. Query filter cả 2 chưa được cover bởi index hiện tại:
```sql
-- Cần composite index (store_id, is_active) WHERE deleted_at IS NULL
SELECT * FROM products WHERE store_id = ? AND is_active = true AND deleted_at IS NULL;
```

---

### 11d — Aggregation & reporting

**Vấn đề #1 — Báo cáo doanh thu tháng sẽ full scan**

```sql
-- Query này chạy mỗi khi chủ quán xem báo cáo tháng
SELECT DATE_TRUNC('month', created_at) AS month,
       SUM(total_amount) AS revenue,
       COUNT(*) AS order_count
FROM orders
WHERE store_id = ? AND status = 'COMPLETED'
  AND created_at BETWEEN '2024-01-01' AND '2024-12-31'
GROUP BY 1
ORDER BY 1;
```

Với 500.000 orders/năm, query này chạy mỗi lần xem dashboard → chậm dần theo thời gian.

**Fix đề xuất — Materialized View (PostgreSQL):**
```sql
CREATE MATERIALIZED VIEW mv_monthly_revenue AS
SELECT store_id,
       DATE_TRUNC('month', created_at) AS month,
       SUM(total_amount) AS revenue,
       SUM(paid_amount) AS collected,
       COUNT(*) AS order_count
FROM orders
WHERE status = 'COMPLETED'
GROUP BY store_id, DATE_TRUNC('month', created_at);

CREATE UNIQUE INDEX ON mv_monthly_revenue (store_id, month);

-- Refresh định kỳ (VD: mỗi giờ hoặc sau mỗi order COMPLETED)
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_revenue;
```

**Vấn đề #2 — Báo cáo tồn kho dưới mức tối thiểu**

```sql
-- Phải JOIN 3 bảng, không có index cover cho min_stock_level
SELECT p.name, p.sku, p.min_stock_level, i.quantity, w.name
FROM inventory i
JOIN products p ON p.id = i.product_id
JOIN warehouses w ON w.id = i.warehouse_id
WHERE p.store_id = ?
  AND i.quantity < p.min_stock_level
  AND p.deleted_at IS NULL;
```

Cần thêm index: `CREATE INDEX ON inventory (product_id, quantity)` để tránh filter trên toàn bảng.

**Vấn đề #3 — `debt_balance` là denorm đúng hướng**

Đây là điểm sáng — thay vì `SUM(orders.debt_amount)` mỗi lần hiển thị, dùng `customers.debt_balance` trực tiếp. Cần giữ và enforce nghiêm.

---

### 11e — JPA anti-patterns

**Anti-pattern #1 — `inventory_transactions.reference_type / reference_id` (còn sót)**

Đây là **polymorphic FK không ràng buộc** — pattern nguy hiểm trong JPA:

```java
// Không thể map bằng @ManyToOne thông thường
// Phải dùng @Any của Hibernate — phức tạp, ít dùng, khó debug
@Any(metaColumn = @Column(name = "reference_type"))
@AnyMetaDef(...)
private Object reference; // Hibernate @Any
```

Trong thực tế dev thường bỏ qua và query thủ công:
```java
// Dẫn đến native query, không type-safe, không benefit từ JPA
@Query(value = "SELECT * FROM inventory_transactions WHERE reference_type = :type AND reference_id = :id", nativeQuery = true)
```

**Hậu quả:** Không thể dùng Spring Data derived queries, không có FK enforcement, dễ orphan data.

**Fix:** Tách thành 2 nullable FK rõ ràng (giống payments đã sửa):
```sql
order_id           BIGINT  FK → orders          (null nếu từ purchase_order)
purchase_order_id  BIGINT  FK → purchase_orders (null nếu từ order)
-- CHECK: không cần thiết vì MANUAL transaction có thể null cả 2
```

---

**Anti-pattern #2 — `subscriptions.max_*` là cấu hình nhúng trong data row**

```java
// Khi check giới hạn gói, phải query subscriptions mỗi lần tạo order/product/staff
Subscription sub = subscriptionRepository.findByStoreId(storeId);
if (sub.getMaxOrders() != null && currentMonthOrders >= sub.getMaxOrders()) {
    throw new LimitExceededException();
}
```

Không phải anti-pattern nghiêm trọng, nhưng logic này sẽ bị gọi rất thường xuyên (mỗi lần tạo đơn). Nên cache `subscriptions` ở application layer (Redis hoặc local cache với TTL ngắn).

---

**Anti-pattern #3 — Nhiều nullable FK tạo cartesian join risk**

`payments` có `customer_id` nullable và `supplier_id` nullable. Nếu viết JPQL không cẩn thận:

```java
// Nguy hiểm — Hibernate có thể sinh CROSS JOIN nếu navigate qua nullable association
@Query("SELECT p FROM Payment p WHERE p.customer.store.id = :storeId")
// → Hibernate sinh: FROM payments p, customers c, stores s WHERE ...
// → Thực ra là implicit cross join, không dùng LEFT JOIN
```

**Fix:**
```java
// Luôn dùng explicit JOIN trong JPQL khi FK nullable
@Query("SELECT p FROM Payment p LEFT JOIN p.customer c WHERE p.storeId = :storeId")
```

---

## 3. Fix đề xuất theo ưu tiên

### 🔴 Gấp — ✅ Đã xử lý trong DATABASE_SCHEMA.md

**✅ Fix `inventory_transactions` — bỏ polymorphic, thêm FK rõ ràng:**
- Bỏ `reference_type`, `reference_id`
- Thêm `order_id FK → orders` và `purchase_order_id FK → purchase_orders`
- Thêm index `idx_inv_tx_order_id` và `idx_inv_tx_po_id`

**✅ Thêm index cho `deleted_at IS NULL + is_active`:**
- `idx_products_store_active ON products (store_id, is_active) WHERE deleted_at IS NULL`
- `idx_warehouses_store_active ON warehouses (store_id, is_active) WHERE deleted_at IS NULL`

**✅ Thêm index cho `debt_balance`:**
- `idx_customers_store_debt ON customers (store_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0`
- `idx_suppliers_store_debt ON suppliers (store_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0`

---

### 🟡 Nên làm — ✅ Đã xử lý trong DATABASE_SCHEMA.md

**✅ Materialized Views đã thêm vào section 11:**
- `mv_monthly_revenue` — doanh thu theo tháng
- `mv_inventory_summary` — tồn kho tổng hợp + cảnh báo dưới mức tối thiểu

**Implement keyset pagination cho orders (cần làm ở tầng code):**
```java
@Query("""
    SELECT o FROM Order o
    WHERE o.storeId = :storeId
      AND o.deletedAt IS NULL
      AND (o.createdAt < :lastCreatedAt
           OR (o.createdAt = :lastCreatedAt AND o.id < :lastId))
    ORDER BY o.createdAt DESC, o.id DESC
    """)
List<Order> findNextPage(@Param("storeId") Long storeId,
                         @Param("lastCreatedAt") Instant lastCreatedAt,
                         @Param("lastId") Long lastId,
                         Pageable pageable);
```

**Fix N+1 — dùng JOIN FETCH hoặc @EntityGraph (cần làm ở tầng code):**

```java
// Order với items — dùng khi cần hiển thị chi tiết đơn hàng
@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.storeId = :storeId AND o.deletedAt IS NULL")
List<Order> findByStoreIdWithItems(@Param("storeId") Long storeId);

// Store với members và user info — dùng khi cần danh sách nhân viên
@EntityGraph(attributePaths = {"members", "members.user"})
Optional<Store> findWithMembersById(Long id);

// Product với inventory — dùng khi cần hiển thị tồn kho
@Query("SELECT p FROM Product p LEFT JOIN FETCH p.inventories WHERE p.storeId = :storeId AND p.isActive = true AND p.deletedAt IS NULL")
List<Product> findActiveWithInventory(@Param("storeId") Long storeId);
```

**Fix partial index — luôn filter `deletedAt IS NULL` trong repository (cần làm ở tầng code):**

```java
// SAI — Hibernate không dùng partial index
List<Product> findByStoreIdAndIsActive(Long storeId, boolean isActive);

// ĐÚNG — thêm deletedAt condition để PostgreSQL dùng được partial index
List<Product> findByStoreIdAndIsActiveAndDeletedAtIsNull(Long storeId, boolean isActive);
```

**Cache subscriptions:**
```java
@Cacheable(value = "subscriptions", key = "#storeId")
public Subscription getByStoreId(Long storeId) {
    return subscriptionRepository.findByStoreId(storeId).orElseThrow();
}
```

---

### 🟢 Tùy chọn

- Dùng `@BatchSize(size = 20)` trên các `@OneToMany` làm fallback khi không JOIN FETCH được
- Materialized View cho báo cáo công nợ tổng hợp khi số store lớn (> 10.000)
- Xem xét read replica cho các query báo cáo nặng

---

## 4. Query mẫu nguy hiểm

### Query nguy hiểm #1 — Dashboard trang chủ chủ quán

```jpql
SELECT o FROM Order o
WHERE o.store.id = :storeId
ORDER BY o.createdAt DESC
```

**Tại sao nguy hiểm:**
- `o.store.id` thay vì `o.storeId` → Hibernate sinh `JOIN stores s ON s.id = o.store_id` thừa
- Không có `deleted_at IS NULL` filter trên store → scan thêm rows
- Nếu sau đó gọi `o.getItems()` → N+1

**Fix:**
```jpql
SELECT o FROM Order o JOIN FETCH o.items
WHERE o.storeId = :storeId AND o.deletedAt IS NULL
ORDER BY o.createdAt DESC
```

---

### Query nguy hiểm #2 — Danh sách sản phẩm với tồn kho

```jpql
SELECT p FROM Product p WHERE p.storeId = :storeId AND p.isActive = true
```

**Tại sao nguy hiểm:**
- Không filter `deletedAt IS NULL` → partial index không được dùng, full scan
- Nếu UI hiển thị tồn kho → gọi `inventoryRepository.findByProductId()` cho từng product → N+1
- Với 500 sản phẩm → 501 queries

**Fix:**
```jpql
SELECT p FROM Product p
LEFT JOIN FETCH p.inventories i
WHERE p.storeId = :storeId
  AND p.isActive = true
  AND p.deletedAt IS NULL
```

---

### Query nguy hiểm #3 — Báo cáo công nợ khách hàng

```jpql
SELECT c FROM Customer c
WHERE c.storeId = :storeId AND c.debtBalance > 0
ORDER BY c.debtBalance DESC
```

**Tại sao nguy hiểm:**
- Không có index trên `debt_balance` → full scan toàn bộ customers của store
- Nếu sau đó load orders của từng customer để hiển thị lịch sử → N+1 nghiêm trọng
- Với 1000 khách hàng có công nợ → 1001 queries

**Fix:**
```jpql
SELECT c FROM Customer c
LEFT JOIN FETCH c.orders o
WHERE c.storeId = :storeId
  AND c.debtBalance > 0
  AND c.deletedAt IS NULL
  AND (o IS NULL OR o.status = 'COMPLETED')
ORDER BY c.debtBalance DESC
```

Và thêm index:
```sql
CREATE INDEX idx_customers_store_debt ON customers (store_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0;
```
