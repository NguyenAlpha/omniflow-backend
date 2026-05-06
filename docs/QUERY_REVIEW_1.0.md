# Đánh giá Query-Friendliness 1.0 — OmniFlow

> **Vai trò đánh giá:** Database Architect chuyên PostgreSQL + Spring Data JPA
> **Ngày đánh giá:** 2026-05-06
> **Phiên bản schema:** 1.1 (23 bảng + 2 materialized views + 38+ indexes)
> **Stack:** PostgreSQL · Spring Boot 3.x · Spring Data JPA (Hibernate 6)

---

## 1. Bảng điểm

| Nhóm | Điểm /10 | Vấn đề chính |
|---|---|---|
| 11a. JOIN complexity | 8/10 | Core queries ≤ 3 JOIN; báo cáo công nợ tổng hợp vẫn cần 4–5 bảng |
| 11b. N+1 query risk | 6/10 | `Order → items → product` và `PurchaseOrder → items → product` là 2 điểm N+1 chắc chắn xuất hiện |
| 11c. Filter / sort / pagination | 7/10 | Index composite đã đủ; thiếu hướng dẫn keyset pagination và `deletedAt IS NULL` discipline |
| 11d. Aggregation & reporting | 8/10 | 2 materialized views đã giải quyết bottleneck lớn nhất; còn thiếu MV cho công nợ |
| 11e. JPA anti-patterns | 8/10 | Polymorphic FK đã được fix toàn bộ; còn `nullable FK` trong `payments` cần JPQL cẩn thận |

### Điểm trung bình tiêu chí 11: **7.4 / 10**

> **Cải thiện so với review trước (5.6/10):** +1.8 điểm.
> ✅ `inventory_transactions` polymorphic → `order_id` + `purchase_order_id` có FK thật
> ✅ Materialized views thêm cho `mv_monthly_revenue` + `mv_inventory_summary`
> ✅ 35+ indexes bao phủ composite, partial, debt filter
> ✅ `units` thêm `store_id` nullable — query units cần pattern mới (xem 11e)
> ✅ `price_history` + `return_order_items` thêm `store_id` + index trực tiếp

---

## 2. Vấn đề cụ thể

### 11a — JOIN complexity

**Tốt:** Các query core của POS chỉ cần 2–3 JOIN — thiết kế clean.

```sql
-- Danh sách đơn hàng của store — 2 JOIN
SELECT o.*, c.name AS customer_name, w.name AS warehouse_name
FROM orders o
LEFT JOIN customers  c ON c.id = o.customer_id
JOIN     warehouses  w ON w.id = o.warehouse_id
WHERE o.store_id = ? AND o.status = 'PENDING'
ORDER BY o.created_at DESC;

-- Nhân viên của store — 1 JOIN
SELECT u.full_name, u.email, sm.role, sm.position_title
FROM store_members sm
JOIN users u ON u.id = sm.user_id
WHERE sm.store_id = ? AND sm.deleted_at IS NULL;
```

**Vấn đề còn lại:** Màn hình báo cáo công nợ tổng hợp cần 4 bảng, Hibernate dễ sinh sub-select không tối ưu:

```sql
SELECT c.name,
       c.debt_balance,
       COUNT(DISTINCT o.id)  AS total_orders,
       COALESCE(SUM(p.amount), 0) AS total_paid
FROM customers c
LEFT JOIN orders   o ON o.customer_id = c.id AND o.store_id = ? AND o.status = 'COMPLETED'
LEFT JOIN payments p ON p.customer_id = c.id AND p.store_id = ?
WHERE c.store_id = ? AND c.deleted_at IS NULL AND c.debt_balance > 0
GROUP BY c.id, c.name, c.debt_balance
ORDER BY c.debt_balance DESC;
```

Với 1.000+ khách hàng có công nợ, query này nên chạy từ materialized view thay vì live aggregation.

---

### 11b — N+1 query risk

#### Điểm nguy hiểm #1 — `Order → OrderItems → Product`

Rủi ro cao nhất trong toàn hệ thống. Màn hình chi tiết đơn hàng và in hóa đơn đều cần dữ liệu này:

```java
// BUG: N+1 — mỗi order trigger 1 query lấy items, mỗi item trigger 1 query lấy product
List<Order> orders = orderRepository.findByStoreId(storeId);
orders.forEach(o -> {
    o.getItems().forEach(item -> {
        item.getProduct().getName(); // 1 + N + N*M queries
    });
});
```

**Fix:**
```java
// Repository
@Query("""
    SELECT DISTINCT o FROM Order o
    JOIN FETCH o.items i
    JOIN FETCH i.product
    WHERE o.storeId = :storeId
      AND o.deletedAt IS NULL
    ORDER BY o.createdAt DESC
    """)
List<Order> findByStoreIdWithItemsAndProducts(@Param("storeId") Long storeId);

// Hoặc dùng @EntityGraph cho endpoint chi tiết 1 đơn
@EntityGraph(attributePaths = {"items", "items.product"})
Optional<Order> findWithDetailById(Long id);
```

---

#### Điểm nguy hiểm #2 — `PurchaseOrder → Items → Product`

Cùng pattern với Order, cùng rủi ro. Màn hình nhập hàng và kiểm tra đơn nhập đều bị ảnh hưởng:

```java
@Query("""
    SELECT DISTINCT po FROM PurchaseOrder po
    JOIN FETCH po.items i
    JOIN FETCH i.product
    WHERE po.storeId = :storeId
      AND po.status = 'PENDING'
    """)
List<PurchaseOrder> findPendingWithItems(@Param("storeId") Long storeId);
```

---

#### Điểm nguy hiểm #3 — `Store → StoreMembers → Users`

Màn hình quản lý nhân sự cần join qua 2 bảng:

```java
// BUG: 1 + N queries
Store store = storeRepository.findById(id).get();
store.getMembers().forEach(m -> m.getUser().getFullName()); // lazy load từng user

// Fix
@EntityGraph(attributePaths = {"members", "members.user"})
Optional<Store> findWithMembersById(Long id);
```

---

#### Điểm nguy hiểm #4 — `ReturnOrder → Items → Product`

Màn hình lịch sử hoàn trả ít được chú ý nhưng cùng pattern:

```java
@Query("""
    SELECT DISTINCT ro FROM ReturnOrder ro
    JOIN FETCH ro.items i
    JOIN FETCH i.product
    WHERE ro.storeId = :storeId
    ORDER BY ro.createdAt DESC
    """)
List<ReturnOrder> findByStoreIdWithItems(@Param("storeId") Long storeId);
```

---

#### Điểm nguy hiểm #5 — `InventoryTransaction` không có quan hệ ngược

`inventory_transactions` không có navigation về `Order` hay `PurchaseOrder` theo chiều ngược. Muốn lấy transaction kèm thông tin đơn hàng nguồn phải viết native query — không type-safe, miss JPA benefit:

```java
// Bắt buộc native query vì không có @ManyToOne trỏ ngược về
@Query(value = """
    SELECT it.*, o.order_code, po.order_code AS po_code
    FROM inventory_transactions it
    LEFT JOIN orders o ON o.id = it.order_id
    LEFT JOIN purchase_orders po ON po.id = it.purchase_order_id
    WHERE it.store_id = :storeId
    ORDER BY it.created_at DESC
    """, nativeQuery = true)
List<Object[]> findWithSourceInfo(@Param("storeId") Long storeId);
```

**Giải pháp:** Thêm `@ManyToOne` vào entity `InventoryTransaction` trỏ về `Order` và `PurchaseOrder` (nullable). Sau đó dùng `LEFT JOIN FETCH` bình thường.

---

### 11c — Filter / sort / pagination

**Tốt:** Index composite `(store_id, status)`, `(store_id, created_at DESC)` trên `orders`, `purchase_orders`, `inventory_transactions` — cover các query phổ biến nhất.

**Partial index debt filter** — điểm sáng:
```sql
-- Index này cover trực tiếp query "danh sách khách có công nợ"
CREATE INDEX idx_customers_store_debt
  ON customers (store_id, debt_balance DESC)
  WHERE deleted_at IS NULL AND debt_balance > 0;
```

---

#### Vấn đề #1 — `deletedAt IS NULL` discipline trong JPA

Partial index chỉ được dùng khi query có đúng condition `deleted_at IS NULL`. Hibernate không tự thêm condition này — developer phải nhớ:

```java
// SAI — PostgreSQL không dùng partial index, full scan
@Repository
interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByStoreIdAndIsActive(Long storeId, boolean isActive);
    // → WHERE store_id = ? AND is_active = ?  ← thiếu deleted_at IS NULL
}

// ĐÚNG — PostgreSQL dùng idx_products_store_active
List<Product> findByStoreIdAndIsActiveAndDeletedAtIsNull(Long storeId, boolean isActive);
// → WHERE store_id = ? AND is_active = ? AND deleted_at IS NULL ✓
```

**Khuyến nghị:** Dùng `@Where(clause = "deleted_at IS NULL")` trên entity để Hibernate tự inject condition:

```java
@Entity
@Table(name = "products")
@Where(clause = "deleted_at IS NULL")  // Hibernate tự thêm vào mọi query
public class Product { ... }
```

Lưu ý: `@Where` là Hibernate-specific, không dùng được khi cần query `deleted_at IS NOT NULL` (VD: admin view). Trong trường hợp đó cần fallback native query.

---

#### Vấn đề #2 — OFFSET pagination sẽ chậm khi bảng lớn

`orders` và `inventory_transactions` sẽ đạt hàng triệu rows sau vài tháng vận hành. Spring Data `Page<T>` dùng OFFSET — chậm dần ở trang cuối:

```sql
-- Hibernate sinh ra khi dùng Pageable
SELECT * FROM orders WHERE store_id = ?
ORDER BY created_at DESC
LIMIT 20 OFFSET 10000;  -- PostgreSQL phải đọc 10020 rows để skip 10000
```

**Fix — Keyset pagination (cursor-based):**

```java
// Schema đã sẵn sàng: orders có id (BIGSERIAL) + created_at (TIMESTAMPTZ)
@Query("""
    SELECT o FROM Order o
    WHERE o.storeId = :storeId
      AND o.deletedAt IS NULL
      AND (o.createdAt < :lastCreatedAt
           OR (o.createdAt = :lastCreatedAt AND o.id < :lastId))
    ORDER BY o.createdAt DESC, o.id DESC
    """)
List<Order> findNextPage(
    @Param("storeId")       Long storeId,
    @Param("lastCreatedAt") Instant lastCreatedAt,
    @Param("lastId")        Long lastId,
    Pageable pageable       // chỉ dùng LIMIT, không dùng OFFSET
);
```

Composite index `(store_id, created_at DESC)` đã có — keyset pagination sẽ dùng được ngay.

---

#### Vấn đề #3 — Thiếu index cho sort theo `name` trong search

```sql
-- Tìm sản phẩm theo tên — query phổ biến khi tạo đơn hàng
SELECT * FROM products
WHERE store_id = ?
  AND name ILIKE '%cà phê%'
  AND deleted_at IS NULL;
```

`ILIKE '%...%'` không dùng được B-tree index. Cần GIN index với `tsvector` cho full-text search (xem Section 4 — Fix đề xuất).

---

### 11d — Aggregation & reporting

**Tốt:** 2 materialized views đã giải quyết 2 bottleneck lớn nhất:

- `mv_monthly_revenue` — doanh thu/tháng theo store: dashboard chủ quán không còn aggregate trực tiếp trên `orders`
- `mv_inventory_summary` — tồn kho + cảnh báo thấp: không còn JOIN `products + inventory` mỗi lần load

---

#### Vấn đề còn lại #1 — Báo cáo công nợ tổng hợp chưa có MV

Query tổng hợp công nợ vẫn cần aggregate live:

```sql
-- Chạy mỗi lần admin/manager xem báo cáo công nợ
SELECT c.id, c.name, c.debt_balance,
       COUNT(o.id)        AS overdue_orders,
       SUM(o.debt_amount) AS total_debt_from_orders
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id AND o.status = 'COMPLETED' AND o.debt_amount > 0
WHERE c.store_id = ? AND c.deleted_at IS NULL
GROUP BY c.id, c.name, c.debt_balance
ORDER BY c.debt_balance DESC;
```

**Fix đề xuất:**
```sql
CREATE MATERIALIZED VIEW mv_customer_debt_summary AS
SELECT c.store_id,
       c.id          AS customer_id,
       c.name,
       c.debt_balance,
       COUNT(o.id)   AS pending_orders_with_debt,
       MAX(o.created_at) AS last_order_at
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
                   AND o.status = 'COMPLETED'
                   AND o.debt_amount > 0
WHERE c.deleted_at IS NULL
GROUP BY c.store_id, c.id, c.name, c.debt_balance;

CREATE UNIQUE INDEX ON mv_customer_debt_summary (store_id, customer_id);
CREATE INDEX ON mv_customer_debt_summary (store_id, debt_balance DESC)
  WHERE debt_balance > 0;
```

---

#### Vấn đề còn lại #2 — Refresh strategy chưa được implement

MV chỉ hữu ích nếu được refresh đúng lúc. Schema document có nêu `@TransactionalEventListener` nhưng chưa có cơ chế:

```java
// Cần implement trong OrderService
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderCompleted(OrderCompletedEvent event) {
    // Refresh mv_monthly_revenue — light, chạy được sau mỗi order
    entityManager.createNativeQuery(
        "REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_revenue"
    ).executeUpdate();
}

// inventory_transactions xảy ra thường xuyên hơn — nên batch
// Dùng @Scheduled(fixedDelay = 300_000) // mỗi 5 phút
@Scheduled(fixedDelay = 300_000)
public void refreshInventorySummary() {
    entityManager.createNativeQuery(
        "REFRESH MATERIALIZED VIEW CONCURRENTLY mv_inventory_summary"
    ).executeUpdate();
}
```

---

### 11e — JPA anti-patterns

**Tốt:** Polymorphic FK đã được fix hoàn toàn:
- ✅ `payments` → `customer_id` + `supplier_id` tách biệt + CHECK constraint
- ✅ `inventory_transactions` → `order_id` + `purchase_order_id` tách biệt
- ✅ `units` thêm `store_id` nullable — không còn rủi ro một tenant xoá unit ảnh hưởng tenant khác

**`units` — query pattern bắt buộc sau khi thêm `store_id`:**

```java
// SAI — chỉ lấy system units, bỏ sót custom units của store
List<Unit> findByDeletedAtIsNull();

// SAI — chỉ lấy custom units của store, bỏ sót system units
List<Unit> findByStoreIdAndDeletedAtIsNull(Long storeId);

// ĐÚNG — lấy cả system units (store_id IS NULL) lẫn units riêng của store
@Query("SELECT u FROM Unit u WHERE (u.storeId = :storeId OR u.storeId IS NULL) AND u.deletedAt IS NULL ORDER BY u.name")
List<Unit> findAvailableForStore(@Param("storeId") Long storeId);
```

Lưu ý: JPQL không hỗ trợ `IS NULL` trực tiếp trên join path — luôn dùng `u.storeId IS NULL` (field name), không phải `u.store IS NULL`.

---

#### Anti-pattern còn lại #1 — `payments` với 2 nullable FK dễ gây implicit cross join

`payments` có `customer_id nullable` và `supplier_id nullable`. Nếu navigate qua association trong JPQL không cẩn thận:

```java
// NGUY HIỂM — Hibernate có thể sinh implicit cross join
@Query("SELECT p FROM Payment p WHERE p.customer.store.id = :storeId")
// Hibernate sinh: FROM payments p, customers c, stores s
// WHERE c.id = p.customer_id AND s.id = c.store_id
// → CROSS JOIN thay vì LEFT JOIN → bỏ sót payment không có customer

// ĐÚNG — explicit JOIN
@Query("SELECT p FROM Payment p LEFT JOIN p.customer c WHERE p.storeId = :storeId")
```

**Rule:** Với mọi nullable FK (`@ManyToOne(optional = true)`), luôn dùng `LEFT JOIN` explicit trong JPQL — không navigate qua `p.customer.store.id`.

---

#### Anti-pattern còn lại #2 — `subscriptions.max_*` check trên mỗi request

```java
// Được gọi MỖI LẦN tạo order, tạo product, thêm nhân viên
Subscription sub = subscriptionRepository.findByStoreId(storeId);
if (sub.getMaxOrdersPerMonth() != null && monthlyOrderCount >= sub.getMaxOrdersPerMonth()) {
    throw new LimitExceededException();
}
```

Không phải anti-pattern nghiêm trọng nhưng `subscriptions` sẽ là hot row — mọi store đều đọc bảng này thường xuyên.

**Fix — Cache tầng application:**
```java
@Service
public class SubscriptionService {

    @Cacheable(value = "subscriptions", key = "#storeId")
    public Subscription getByStoreId(Long storeId) {
        return subscriptionRepository.findByStoreId(storeId).orElseThrow();
    }

    @CacheEvict(value = "subscriptions", key = "#storeId")
    public void updateSubscription(Long storeId, ...) { ... }
}
```

---

#### Anti-pattern còn lại #3 — `audit_logs.old_data / new_data` JSONB

```java
// audit_logs lưu JSONB — không thể filter trực tiếp trong Spring Data
@Query("SELECT a FROM AuditLog a WHERE a.tableName = :table AND a.recordId = :id")
List<AuditLog> findByTableAndRecord(...); // OK

// NGUY HIỂM — filter bên trong JSONB không có index
@Query(value = """
    SELECT * FROM audit_logs
    WHERE old_data->>'status' = 'PENDING'
    AND new_data->>'status' = 'COMPLETED'
    """, nativeQuery = true)
// → Full scan toàn bộ audit_logs
```

JSONB ở `audit_logs` là **đúng hướng** vì chỉ cần đọc toàn bộ object, không filter bên trong. Tuyệt đối không thêm filter trên `old_data->>'column'` — nếu cần filter thì cần thêm cột riêng cho trường đó.

---

## 3. Fix đề xuất theo ưu tiên

### 🔴 Gấp — làm trước khi viết code Repository

**Thêm `@Where` hoặc enforce `deletedAt IS NULL` nhất quán:**

```java
// Option A: @Where trên entity (Hibernate-specific)
@Entity
@Where(clause = "deleted_at IS NULL")
public class Product { ... }

// Option B: Base repository method có sẵn filter
public interface ProductRepository extends JpaRepository<Product, Long> {
    // Mọi method đều phải có AndDeletedAtIsNull
    List<Product> findByStoreIdAndDeletedAtIsNull(Long storeId);
    Optional<Product> findByIdAndDeletedAtIsNull(Long id);
}
```

**Lý do:** Partial index chỉ được dùng khi query có `deleted_at IS NULL`. Nếu miss condition này, PostgreSQL fallback sang full scan — hiệu năng giảm đột ngột khi data lớn.

---

**Implement JOIN FETCH cho 4 query N+1 chắc chắn xảy ra:**

```java
// 1. Order detail
@EntityGraph(attributePaths = {"items", "items.product"})
Optional<Order> findWithDetailById(Long id);

// 2. Order list with items (cho in hóa đơn, xuất CSV)
@Query("SELECT DISTINCT o FROM Order o JOIN FETCH o.items i JOIN FETCH i.product WHERE o.storeId = :storeId AND o.deletedAt IS NULL ORDER BY o.createdAt DESC")
List<Order> findByStoreIdWithItems(@Param("storeId") Long storeId);

// 3. PurchaseOrder detail
@EntityGraph(attributePaths = {"items", "items.product"})
Optional<PurchaseOrder> findWithDetailById(Long id);

// 4. ReturnOrder detail
@EntityGraph(attributePaths = {"items", "items.product"})
Optional<ReturnOrder> findWithDetailById(Long id);
```

---

### 🟡 Nên làm — trong sprint đầu tiên

**Keyset pagination cho `orders` và `inventory_transactions`:**

```java
// Schema đã hỗ trợ — chỉ cần implement repository method
@Query("""
    SELECT o FROM Order o
    WHERE o.storeId = :storeId
      AND o.deletedAt IS NULL
      AND (o.createdAt < :lastCreatedAt
           OR (o.createdAt = :lastCreatedAt AND o.id < :lastId))
    ORDER BY o.createdAt DESC, o.id DESC
    """)
List<Order> findNextPage(
    @Param("storeId")       Long storeId,
    @Param("lastCreatedAt") Instant lastCreatedAt,
    @Param("lastId")        Long lastId,
    Pageable pageable
);
```

**Cache subscriptions:**

```java
@Cacheable(value = "subscriptions", key = "#storeId")
public Subscription getByStoreId(Long storeId) { ... }
```

**Thêm MV cho công nợ khách hàng** — xem SQL ở Section 11d.

**Implement refresh strategy cho materialized views** — xem code `@TransactionalEventListener` ở Section 11d.

---

### 🟢 Tùy chọn — roadmap sau

**Full-text search với GIN index:**
```sql
ALTER TABLE products  ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(name, '') || ' ' || coalesce(sku, ''))
    ) STORED;

ALTER TABLE customers ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(name, '') || ' ' || coalesce(phone, ''))
    ) STORED;

CREATE INDEX idx_products_fts  ON products  USING GIN (search_vector);
CREATE INDEX idx_customers_fts ON customers USING GIN (search_vector);
```

**`@BatchSize` fallback khi không dùng JOIN FETCH được:**
```java
@OneToMany(mappedBy = "order", fetch = FetchType.LAZY)
@BatchSize(size = 30)  // Hibernate load 30 collections trong 1 query thay vì N queries
private List<OrderItem> items;
```

**Read replica cho báo cáo nặng:**
```java
// Route query báo cáo sang read replica khi traffic lớn
@Transactional(readOnly = true)
@Query(...)  // Spring routing tự động sang slave nếu cấu hình AbstractRoutingDataSource
```

---

## 4. Query mẫu nguy hiểm

### Query nguy hiểm #1 — Danh sách sản phẩm đang hoạt động

```jpql
SELECT p FROM Product p
WHERE p.storeId = :storeId AND p.isActive = true
```

**Tại sao nguy hiểm:**
- Thiếu `p.deletedAt IS NULL` → PostgreSQL không dùng được `idx_products_store_active (store_id, is_active) WHERE deleted_at IS NULL` → **full scan**
- Nếu UI hiển thị tồn kho theo kho → gọi `inventoryRepository.findByProductId()` cho từng sản phẩm → **N+1 với 500 sản phẩm = 501 queries**
- Khi store có 50.000 sản phẩm: full scan + 50.001 queries = timeout

**Fix:**
```jpql
SELECT p FROM Product p
LEFT JOIN FETCH p.inventories
WHERE p.storeId = :storeId
  AND p.isActive = true
  AND p.deletedAt IS NULL
```

---

### Query nguy hiểm #2 — Lịch sử giao dịch kho của store

```jpql
SELECT it FROM InventoryTransaction it
WHERE it.store.id = :storeId
ORDER BY it.createdAt DESC
```

**Tại sao nguy hiểm:**
- `it.store.id` thay vì `it.storeId` → Hibernate sinh **implicit JOIN với bảng stores** dù không cần lấy thông tin store
- Không có `LIMIT` → load toàn bộ transaction history (có thể hàng triệu rows)
- `it.store.id` không dùng được index `idx_inv_tx_store_created (store_id, created_at DESC)` bằng câu `it.storeId`

**Fix:**
```jpql
SELECT it FROM InventoryTransaction it
WHERE it.storeId = :storeId
ORDER BY it.createdAt DESC
```

Kết hợp keyset pagination — không dùng `Page<T>`:
```java
@Query("""
    SELECT it FROM InventoryTransaction it
    WHERE it.storeId = :storeId
      AND (it.createdAt < :lastCreatedAt
           OR (it.createdAt = :lastCreatedAt AND it.id < :lastId))
    ORDER BY it.createdAt DESC, it.id DESC
    """)
List<InventoryTransaction> findNextPage(...);
```

---

### Query nguy hiểm #3 — Dashboard tổng hợp: tất cả số liệu một lúc

```java
// Anti-pattern: load nhiều aggregate trong 1 service call
public DashboardDto getDashboard(Long storeId) {
    long pendingOrders     = orderRepository.countByStoreIdAndStatus(storeId, "PENDING");
    BigDecimal revenue     = orderRepository.sumRevenueByStoreId(storeId);      // full scan orders
    long lowStockProducts  = productRepository.countLowStockByStoreId(storeId); // full scan inventory
    long customersWithDebt = customerRepository.countDebtByStoreId(storeId);   // full scan customers
    // 4 queries, 3 full scans → dashboard chậm dần mỗi tháng
}
```

**Tại sao nguy hiểm:**
- `sumRevenueByStoreId` không có MV → aggregate trực tiếp trên `orders` (có thể 500K+ rows)
- `countLowStockByStoreId` phải JOIN `products + inventory` → không có index cover cho `quantity < min_stock_level`
- 4 queries sequential → latency cộng dồn

**Fix:**
```java
public DashboardDto getDashboard(Long storeId) {
    // Đọc từ materialized views — O(1) thay vì O(n)
    MonthlyRevenue revenue  = mvMonthlyRevenueRepo.findByStoreIdAndMonth(storeId, YearMonth.now());
    List<LowStock>  lowStock = mvInventorySummaryRepo.findLowStockByStoreId(storeId);

    // Chỉ pending orders là live query — nhưng đã có composite index (store_id, status)
    long pendingOrders = orderRepository.countByStoreIdAndStatus(storeId, "PENDING");

    // debt_balance đã là denorm — không cần aggregate
    long debtCustomers = customerRepository.countByStoreIdAndDebtBalanceGreaterThanAndDeletedAtIsNull(
        storeId, BigDecimal.ZERO
    ); // dùng idx_customers_store_debt partial index
}
```

---

## 5. So sánh với Query Review trước (QUERY_REVIEW.md — 5.6/10)

| Vấn đề cũ | Trạng thái | Ghi chú |
|---|---|---|
| `inventory_transactions` polymorphic FK | ✅ Đã fix | `order_id` + `purchase_order_id` tách biệt |
| Không có materialized view | ✅ Đã thêm | `mv_monthly_revenue` + `mv_inventory_summary` |
| Index composite thiếu | ✅ Đã thêm | 38+ indexes đầy đủ |
| Debt filter index thiếu | ✅ Đã thêm | Partial index `WHERE debt_balance > 0` |
| `is_active + deleted_at` composite index | ✅ Đã thêm | `idx_products_store_active`, `idx_warehouses_store_active` |
| `units` không có `store_id` | ✅ Đã fix | `store_id` nullable — NULL = system unit; cần query pattern mới |
| `price_history` thiếu `store_id` | ✅ Đã fix | Thêm `store_id NOT NULL` + `idx_price_history_store_created` |
| `return_order_items` thiếu `store_id` | ✅ Đã fix | Thêm `store_id NOT NULL` + `idx_return_order_items_store_id` |
| N+1 `Order → items → product` | ⚠️ Còn tồn đọng | Schema sẵn sàng, cần implement JOIN FETCH trong repository |
| N+1 `PurchaseOrder → items → product` | ⚠️ Còn tồn đọng | Cùng pattern, cần xử lý khi viết repository |
| OFFSET pagination chậm | ⚠️ Còn tồn đọng | Schema hỗ trợ keyset, chưa implement ở tầng code |
| `deletedAt IS NULL` discipline | ⚠️ Còn tồn đọng | Cần quy ước rõ hoặc dùng `@Where` trên entity |
| MV refresh strategy | ⚠️ Còn tồn đọng | Cần implement `@TransactionalEventListener` + `@Scheduled` |
| MV cho công nợ khách hàng | ⚠️ Còn tồn đọng | Chưa có, query live sẽ chậm khi store lớn |
