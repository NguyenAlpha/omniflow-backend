# Database Capabilities — OmniFlow

> Mục đích: Tài liệu hoá những gì **DB đã tự lo** để service layer không cần implement lại.
> Cập nhật khi thêm index / trigger / view mới.
> Stack: PostgreSQL · Spring Data JPA (Hibernate 6) · Flyway

---

## 1. Triggers tự động (không cần xử lý ở service)

### 1.1 `products.search_vector` — tự cập nhật

**Trigger:** `tsvector_update_products` (BEFORE INSERT OR UPDATE)

```sql
new.search_vector := to_tsvector('simple',
    COALESCE(new.name, '') || ' ' ||
    COALESCE(new.sku, '') || ' ' ||
    COALESCE(new.description, '')
);
```

- **Service không cần:** set `search_vector` thủ công khi tạo / sửa product.
- **Dùng đúng cách:** query bằng `search_vector @@ plainto_tsquery('simple', ?)` — không dùng `ILIKE` cho full-text.
- Trường `description` đã được index vào `search_vector` — không cần concat thêm ở tầng Java.

---

### 1.2 `customers.search_vector` — tự cập nhật

**Trigger:** `tsvector_update_customers` (BEFORE INSERT OR UPDATE)

```sql
new.search_vector := to_tsvector('simple',
    COALESCE(new.name, '') || ' ' ||
    COALESCE(new.code, '') || ' ' ||
    COALESCE(new.phone, '') || ' ' ||
    COALESCE(new.email, '')
);
```

- **Service không cần:** set `search_vector` thủ công khi tạo / sửa customer.
- Tìm kiếm customer theo name / code / phone / email đều dùng chung 1 cột `search_vector`.

---

## 2. Materialized Views (dữ liệu pre-aggregate)

> **Lưu ý:** Materialized view không tự refresh — phải gọi `REFRESH MATERIALIZED VIEW CONCURRENTLY` theo lịch hoặc sau nghiệp vụ liên quan.

### 2.1 `mv_monthly_revenue`

```sql
-- Columns: business_id, store_id, month (DATE), revenue, collected, uncollected, order_count
SELECT s.business_id, o.store_id, DATE_TRUNC('month', o.created_at)::DATE AS month,
       SUM(total_amount), SUM(paid_amount), SUM(debt_amount), COUNT(*)
FROM orders o JOIN stores s ON s.id = o.store_id
WHERE o.status = 'COMPLETED'
GROUP BY s.business_id, o.store_id, month;
```

- **Dùng cho:** dashboard doanh thu tháng, báo cáo tổng hợp.
- **Unique index:** `(store_id, month)` — query `WHERE store_id = ? AND month = ?` sẽ index scan.
- **Index thêm:** `(business_id, month)` — query tổng doanh thu toàn business.
- **Không dùng cho:** doanh thu real-time (delay đến lần refresh tiếp theo).

---

### 2.2 `mv_inventory_summary`

```sql
-- Columns: business_id, product_id, product_name, sku, min_stock_level, total_stock, is_low_stock
```

- **Dùng cho:** màn hình cảnh báo hàng sắp hết (`is_low_stock = true`), báo cáo tồn kho toàn business.
- **Unique index:** `(business_id, product_id)`.
- **Index:** `(business_id, is_low_stock) WHERE is_low_stock = true` — rất nhanh cho query cảnh báo.
- **Không dùng cho:** tồn kho real-time trong luồng bán hàng — dùng `products.total_stock` thay thế.

---

## 3. Index catalog

### 3.1 Index composite — query nên viết theo thứ tự cột này

| Index | Bảng | Cột | Query phù hợp |
|---|---|---|---|
| `idx_orders_store_status` | `orders` | `(store_id, status)` | Lọc đơn theo trạng thái trong 1 store |
| `idx_orders_store_created` | `orders` | `(store_id, created_at DESC)` | Danh sách đơn mới nhất theo store |
| `idx_inv_tx_store_created` | `inventory_transactions` | `(store_id, created_at DESC)` | Lịch sử nhập/xuất kho gần đây |
| `idx_po_store_status` | `purchase_orders` | `(store_id, status)` | Lọc đơn nhập theo trạng thái |
| `idx_inventory_product_warehouse` | `inventory` | `(product_id, warehouse_id)` | Lookup tồn kho tại 1 kho cụ thể |
| `idx_inventory_product_qty` | `inventory` | `(product_id, quantity)` | Aggregate tồn kho theo product |
| `idx_products_business_active` | `products` | `(business_id, is_active)` WHERE `deleted_at IS NULL` | Lọc sản phẩm active / inactive theo business |
| `idx_warehouses_store_active` | `warehouses` | `(store_id, is_active)` WHERE `deleted_at IS NULL` | Danh sách kho đang hoạt động |
| `idx_customers_business_debt` | `customers` | `(business_id, debt_balance DESC)` WHERE `debt_balance > 0` | Xếp hạng khách hàng nợ nhiều nhất |
| `idx_suppliers_business_debt` | `suppliers` | `(business_id, debt_balance DESC)` WHERE `debt_balance > 0` | Xếp hạng nhà cung cấp nợ nhiều nhất |
| `idx_price_history_product_id` | `price_history` | `(product_id, changed_at DESC)` | Lịch sử giá của 1 sản phẩm |
| `idx_price_history_business_created` | `price_history` | `(business_id, changed_at DESC)` | Lịch sử giá toàn business |
| `idx_audit_logs_business_id` | `audit_logs` | `(business_id, created_at DESC)` | Log theo business, mới nhất trước |
| `idx_audit_logs_store_id` | `audit_logs` | `(store_id, created_at DESC)` | Log theo store, mới nhất trước |
| `idx_audit_logs_table_record` | `audit_logs` | `(table_name, record_id)` | Lịch sử thay đổi của 1 record cụ thể |

---

### 3.2 Index partial (WHERE `deleted_at IS NULL`) — chỉ hiệu quả khi WHERE khớp

Các bảng sau có partial index, query **phải có** `deleted_at IS NULL` để dùng index:

| Index | Bảng | Ghi chú |
|---|---|---|
| `idx_categories_business_id` | `categories(business_id)` | Luôn thêm `AND deleted_at IS NULL` |
| `idx_products_business_id` | `products(business_id)` | Luôn thêm `AND deleted_at IS NULL` |
| `idx_warehouses_store_id` | `warehouses(store_id)` | Luôn thêm `AND deleted_at IS NULL` — warehouse thuộc store |
| `idx_customers_business_id` | `customers(business_id)` | Luôn thêm `AND deleted_at IS NULL` |
| `idx_suppliers_business_id` | `suppliers(business_id)` | Luôn thêm `AND deleted_at IS NULL` |
| `idx_units_business_id` | `units(business_id)` WHERE `business_id IS NOT NULL` | System units (`business_id IS NULL`) không index — đủ nhỏ để seq scan |

---

### 3.3 GIN Index — full-text search

| Index | Cột | Cách dùng |
|---|---|---|
| `idx_products_search_vector` | `products.search_vector` | `WHERE search_vector @@ plainto_tsquery('simple', :term)` |
| `idx_customers_search_vector` | `customers.search_vector` | `WHERE search_vector @@ plainto_tsquery('simple', :term)` |

**Lưu ý quan trọng:**
- `ILIKE '%keyword%'` **không dùng** GIN index — luôn full scan.
- `plainto_tsquery` tự tách từ và xử lý khoảng trắng — không cần preprocess ở Java.
- Tìm kiếm nguyên ký tự (exact substring như mã SKU ngắn) nên kết hợp `ILIKE` với filter `store_id` trước để giảm phạm vi, hoặc dùng `search_vector` nếu SKU đã được index vào đó.

---

### 3.4 Partial UNIQUE Index — soft delete semantics

Các ràng buộc unique chỉ áp dụng cho bản ghi **chưa bị xóa**:

| Index | Ràng buộc |
|---|---|
| `ux_business_members_user_business` | 1 user chỉ có 1 record active trong 1 business |
| `ux_store_members_user_store` | 1 user chỉ có 1 vị trí active trong 1 store |
| `ux_user_roles` | 1 user không có role trùng lặp trong cùng scope (COALESCE NULL→0) |
| `ux_categories_business_name` | Tên category không trùng trong business |
| `ux_units_business_name` | Tên unit không trùng trong business (system units dùng `business_id = 0`) |
| `ux_products_business_sku` | SKU không trùng trong business |
| `ux_customers_business_code` | Mã khách hàng không trùng trong business |
| `ux_suppliers_business_code` | Mã nhà cung cấp không trùng trong business |

Service vẫn nên kiểm tra trước khi insert để trả về lỗi rõ ràng, nhưng DB là lưới an toàn cuối.

---

## 4. Checklist service layer

| Việc | Nơi xử lý | Service cần làm không? |
|---|---|---|
| Cập nhật `search_vector` khi save product | DB trigger | Không |
| Cập nhật `search_vector` khi save customer | DB trigger | Không |
| Tính tổng tồn kho `SUM(inventory.quantity)` | ⚠️ Chưa có trigger | **Có** — service tự cập nhật `total_stock` |
| Tổng doanh thu theo tháng | `mv_monthly_revenue` (business_id + store_id) | Không — query view |
| Danh sách hàng sắp hết theo business | `mv_inventory_summary` (business_id) | Không — query view + filter `is_low_stock` |
| Validate SKU trùng lặp | `ux_products_business_sku` per business | Nên check trước để trả lỗi đẹp |
| Validate tên category / unit trùng | `ux_categories_business_name` per business | Nên check trước để trả lỗi đẹp |
| Sort đơn hàng mới nhất | `idx_orders_store_created` | Viết `ORDER BY created_at DESC` — index đã cover |
| Lọc đơn theo status | `idx_orders_store_status` | Viết `WHERE store_id = ? AND status = ?` — index đã cover |
