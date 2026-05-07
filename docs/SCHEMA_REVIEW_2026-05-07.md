# OmniFlow Database Schema Review (2026-05-07)

Nguon schema: `docs/DATABASE_SCHEMA.md`

## 1) Danh gia tong quan (Tieu chi 1-10)

| Tieu chi | Diem | Nhan xet 1 dong |
|---|---:|---|
| 1. Chuan hoa du lieu | 7 | Chuan hoa tot; denormalized `debt_balance` can ky luat cap nhat nghiem ngat. |
| 2. Khoa & rang buoc | 8 | PK/FK/UNIQUE/CHECK ro rang, nhieu constraint hop ly. |
| 3. Kieu du lieu | 8 | Su dung `NUMERIC(15,2)` cho tien, TIMESTAMPTZ phu hop. |
| 4. Dat ten | 8 | Nhat quan, mo ta ro nghia, convention on. |
| 5. Index & hieu nang | 7 | Index FK + composite hop ly, thieu index cho mot so truy van tim kiem van ban. |
| 6. Toan ven du lieu | 6 | Soft delete ro, nhung thieu enforce cap nhat `debt_balance` va mot so bang thieu `updated_at`. |
| 7. Bao mat | 6 | Co phan quyen logic, nhung chua the hien RLS/tenant isolation tai DB. |
| 8. Kha nang mo rong | 7 | Co `store_id` de scope, nhung chua co partitioning/archiving. |
| 9. Phan anh nghiep vu | 8 | Mo hinh nghiep vu day du (order, return, payment, inventory). |
| 10. Kha nang bao tri | 7 | Co dinh nghia constraint/ index ro, nhung migration zero-downtime can quy trinh. |

**Diem trung binh:** **7.2 / 10**

### Top 3 van de nghiem trong
1. **`debt_balance` denormalized khong co enforce DB** -> de drift, sai so cong no neu service lo transaction.
2. **Thieu co che tenant isolation tai DB (RLS)** -> rui ro leak du lieu giua store neu bug application.
3. **Mot so bang giao dich thieu `updated_at`** (vd: `payments`, `inventory_transactions`, `price_history`) -> kho audit + kho sync/delta.

### Diem manh dang giu lai
- Soft delete ro rang va quy tac khong xoa vat ly o cac bang tai chinh.
- CHECK constraints cho enum-like va gia tri so tien.
- Indexing tuong doi day du, co composite theo nghiep vu.
- Materialized views cho bao cao (revenue, inventory summary).

### Goi y mo rong (3-5)
- **Stock reservation** (dat cho truoc khi thanh toan) de tranh oversell.
- **Product variants** (size, mau, packaging) thay vi hack bằng SKU.
- **Promotion/discount policy** theo nhom san pham/khach hang.
- **Audit trail chi tiet** cho bang nhay cam, them `old_data/new_data` cho nhieu bang hon.
- **File attachments** (hoa don, bien ban) bang `files` + join table.

### Refactor priorities
- **Gap:** Them co che tenant isolation (RLS) hoac tieu chuan filter `store_id` trong moi query.
- **Nen lam:** Bo sung `updated_at` cho bang giao dich, chuan hoa `created_by` o cac bang thieu.
- **Tuy chon:** Bo sung search index (trigram) cho tim kiem theo ten/sku.

---

## 2) Query-friendliness (Tieu chi 11)

| Nhom | Diem /10 | Van de chinh (1 dong) |
|---|---:|---|
| 11a. JOIN complexity | 7 | Cac truy van order->items->product can 2-3 join, chap nhan duoc. |
| 11b. N+1 query risk | 6 | Dung `@OneToMany` co nguy co N+1 khi load orders/items. |
| 11c. Filter/sort/pagination | 7 | Co index theo `store_id, created_at`, thieu keyset pagination index. |
| 11d. Aggregation & reporting | 8 | Da co MV cho doanh thu va ton kho. |
| 11e. JPA anti-patterns | 7 | It JSONB va EAV; mot so FK nullable co the lam cartesian. |

**Diem trung binh tieu chi 11:** **7.0 / 10**

### Van de cu the (bang/cot/query bi anh huong)
- `orders` + `order_items` + `products`: load danh sach don hang kem item/product de bi N+1 neu khong fetch join.
- `products` tim kiem theo `name`/`sku` voi ILIKE `%keyword%` se full scan.
- `inventory` + `products` bao cao ton kho neu thieu filter `store_id` se full scan toan bo.

### Fix de xuat (Gấp / Nên làm / Tùy chọn)
- **Gap:** Dung `@EntityGraph`/`JOIN FETCH` cho `orders -> order_items -> product`.
- **Nen lam:** Them index keyset pagination: `(store_id, created_at, id)` cho `orders`, `inventory_transactions`.
- **Tuy chon:** Dung `pg_trgm` index cho `products(name)` va `sku`.

**SQL/ JPA vi du**
```sql
CREATE INDEX idx_orders_store_created_id ON orders (store_id, created_at DESC, id DESC);
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_products_name_trgm ON products USING GIN (name gin_trgm_ops);
```
```java
@EntityGraph(attributePaths = {"items", "items.product"})
List<Order> findByStoreIdAndCreatedAtBetween(Long storeId, Instant from, Instant to);
```

### Query mau nguy hiem
1. **N+1**
```jpql
SELECT o FROM Order o WHERE o.store.id = :storeId ORDER BY o.createdAt DESC
```
- Sau do truy cap `o.items` -> N+1, can fetch join/entity graph.

2. **Full scan text search**
```sql
SELECT * FROM products WHERE name ILIKE '%cafe%';
```
- Can trigram index hoac search service.

3. **Aggregation khong co filter**
```sql
SELECT p.id, SUM(i.quantity) FROM products p JOIN inventory i ON i.product_id=p.id GROUP BY p.id;
```
- Thieu `WHERE p.store_id = :storeId`, scan toan bo.

---

## 3) Sync local-first/offline-first (S1-S5)

| Nhom | Diem /10 | Van de chinh (1 dong) |
|---|---:|---|
| S1. Timestamp & versioning | 6 | Co `created_at/updated_at`, thieu `sync_version`/sequence. |
| S2. Soft delete readiness | 7 | Nhieu bang co `deleted_at`, nhung bang tai chinh chi co `status`. |
| S3. Conflict detection | 4 | PK `BIGSERIAL`, thieu `device_id/client_id`. |
| S4. Change tracking | 3 | Thieu `change_log/sync_queue` va chua co co che delta. |
| S5. Sync scope & performance | 6 | `store_id` tot, thieu index cho delta sync theo `updated_at`. |

**Diem sync-readiness tong the:** **5.2 /10**

### Thay doi can thiet
**Bat buoc**
- Dung **UUID** cho PK cua cac bang can offline (orders, products, customers, payments, inventory...).
- Them `sync_version BIGINT` hoac `row_version BIGINT` (monotonic) cho delta pull.
- Them bang `change_log`/`sync_queue` theo `store_id`.

**Nen co**
- Them `last_modified_device_id`, `last_modified_by`.
- Chuan hoa `updated_at` cho bang giao dich.

**Tuy chon**
- Them bang `sync_cursors` per device.
- Partition theo `store_id` neu data lon.

**DDL mau**
```sql
ALTER TABLE orders ADD COLUMN uuid UUID DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX uq_orders_uuid ON orders(uuid);

ALTER TABLE orders ADD COLUMN sync_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN last_modified_device_id UUID;

CREATE TABLE change_log (
  id BIGSERIAL PRIMARY KEY,
  store_id BIGINT NOT NULL,
  table_name VARCHAR(50) NOT NULL,
  record_uuid UUID NOT NULL,
  change_type VARCHAR(10) NOT NULL,
  sync_version BIGINT NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  device_id UUID,
  changed_by BIGINT
);
CREATE INDEX idx_change_log_store_version ON change_log (store_id, sync_version);
```

### Conflict risk & chien luoc
- `orders`, `order_items`, `inventory_transactions`: can **merge theo nghiep vu**, khong nen LWW.
- `products`/`price_history`: co the LWW + luu history (da co `price_history`).
- `customers.debt_balance`: su dung transaction log (orders/payments) la nguon truth, `debt_balance` chi cache.

---

## 4) Migration safety (M1-M5)

| Nhom | Diem /10 | Rui ro chinh (1 dong) |
|---|---:|---|
| M1. NOT NULL & default | 6 | Them NOT NULL tren bang lon can pattern add/backfill. |
| M2. Kieu du lieu & resize | 7 | `VARCHAR` co the can mo rong, nhung khong qua nguy hiem. |
| M3. FK & constraint lock | 7 | FK da co index phan lon, can dung `NOT VALID` khi them moi. |
| M4. Rename & drop safety | 7 | Ten bang ro rang; rename co the break JPA neu khong @Column. |
| M5. Zero-downtime readiness | 6 | Chua co guideline expand/contract trong migration. |

**Diem migration safety tong the:** **6.6 /10**

### Diem nguy hiem cu the
- Them cot **NOT NULL** tren bang lon (`orders`, `inventory_transactions`) co the lock.
- Them FK tren bang lon neu chua co index se scan full.
- Rename cot neu JPA mapping implicit -> silent bug.

### Migration script mau (Flyway pattern)
```sql
-- 1) Add nullable
ALTER TABLE payments ADD COLUMN updated_at TIMESTAMPTZ;
-- 2) Backfill
UPDATE payments SET updated_at = created_at WHERE updated_at IS NULL;
-- 3) Set NOT NULL
ALTER TABLE payments ALTER COLUMN updated_at SET NOT NULL;

-- Add FK not valid
ALTER TABLE order_items
  ADD CONSTRAINT fk_order_items_order
  FOREIGN KEY (order_id) REFERENCES orders(id) NOT VALID;
ALTER TABLE order_items VALIDATE CONSTRAINT fk_order_items_order;
```

### Checklist truoc khi migrate production
- Backup + test migration tren staging voi data gan thuc te.
- Dung pattern add nullable -> backfill -> set NOT NULL.
- Tao index cho FK truoc khi add constraint.
- Tach validate constraint thanh buoc rieng.
- Rollout theo expand/contract, deploy app voi backward compatibility.

