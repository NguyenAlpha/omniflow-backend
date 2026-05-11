# Schema Review 1.0 — OmniFlow

> **Vai trò đánh giá:** Database Architect Senior, 10+ năm kinh nghiệm production
> **Ngày đánh giá:** 2026-05-06
> **Phiên bản schema:** 1.0 (23 bảng + 2 materialized views)
> **Context:** Web app / Monolith Spring Boot / PostgreSQL / Multi-tenant POS B2B / Quy mô vừa

---

## 1. Bảng tổng hợp điểm

| # | Tiêu chí | Điểm | Nhận xét 1 dòng |
|---|---|---|---|
| 1 | Chuẩn hóa dữ liệu | 8/10 | 3NF đạt trên hầu hết bảng; vi phạm có chủ đích (`debt_balance`, `total_price`) được document rõ ràng |
| 2 | Khóa & ràng buộc | 8/10 | FK đầy đủ, UNIQUE partial tốt, CHECK constraints bao phủ enum và giá trị số |
| 3 | Kiểu dữ liệu | 8/10 | NUMERIC(15,2) cho tiền, TIMESTAMPTZ cho thời gian, JSONB cho audit — lựa chọn đúng đắn |
| 4 | Đặt tên | 7/10 | snake_case nhất quán, tên rõ nghĩa; một số bảng dùng `created_by`, một số không có |
| 5 | Index & hiệu năng | 8/10 | 35+ indexes bao phủ FK, composite, partial — đủ cho scale vừa; chưa có index cho search toàn văn |
| 6 | Toàn vẹn dữ liệu | 7/10 | Soft delete chuẩn, immutable financials tốt; `debt_balance` vẫn là điểm yếu cần discipline |
| 7 | Bảo mật | 6/10 | `password_hash` đúng, store_id cách ly tenant; thiếu encrypt hint cho PII và chưa có column-level comment |
| 8 | Khả năng mở rộng | 7/10 | Multi-tenant chuẩn, materialized views tốt; thiếu chiến lược partition cho bảng lớn |
| 9 | Phản ánh nghiệp vụ | 8/10 | Đủ core flow POS: bán hàng, nhập hàng, công nợ, hoàn trả, kho — hiếm schema mid-level có đủ |
| 10 | Khả năng bảo trì | 7/10 | Cấu trúc rõ ràng, Flyway migration; `debt_balance` invariant cần test coverage để không drift |

### Điểm trung bình tổng thể: **7.4 / 10**

> **Cải thiện so với review trước (5.8/10):** +1.6 điểm. Ba vấn đề nghiêm trọng cũ đã được giải quyết:
> ✅ Index: 0 → 35+ indexes đầy đủ
> ✅ Polymorphic FK: `reference_id` → `customer_id` + `supplier_id` có FK thật
> ✅ CHECK constraints: thêm trên tất cả enum fields và giá trị số

---

## 2. Top 3 vấn đề nghiêm trọng nhất

### 🔴 Vấn đề 1 — `debt_balance` không có cơ chế enforcement tại DB layer (Tiêu chí 6)

**Mô tả:** `customers.debt_balance` và `suppliers.debt_balance` là giá trị denormalized được tính từ orders/purchase_orders, nhưng không có trigger hay constraint nào tại DB layer đảm bảo đồng bộ. Toàn bộ trách nhiệm thuộc về tầng Application Service.

**Hậu quả nếu không sửa:**
- Bug hoặc race condition trong Service layer dẫn đến số liệu công nợ sai — mất tiền thật
- Khách hàng nợ 5 triệu nhưng hệ thống hiển thị 0, hoặc ngược lại
- Không phát hiện được drift cho đến khi có tranh chấp

**Giải pháp khuyến nghị:**

```sql
-- Invariant verification query — chạy định kỳ (cron job hoặc integration test)
SELECT c.id,
       c.debt_balance                                      AS stored_balance,
       COALESCE(SUM(o.debt_amount) FILTER (WHERE o.status = 'COMPLETED'), 0)
         - COALESCE(SUM(p.amount), 0)                     AS computed_balance,
       c.debt_balance - (
         COALESCE(SUM(o.debt_amount) FILTER (WHERE o.status = 'COMPLETED'), 0)
         - COALESCE(SUM(p.amount), 0)
       )                                                   AS drift
FROM customers c
LEFT JOIN orders  o ON o.customer_id = c.id
LEFT JOIN payments p ON p.customer_id = c.id
GROUP BY c.id, c.debt_balance
HAVING ABS(
  c.debt_balance - (
    COALESCE(SUM(o.debt_amount) FILTER (WHERE o.status = 'COMPLETED'), 0)
    - COALESCE(SUM(p.amount), 0)
  )
) > 0.01;
```

**Bắt buộc áp dụng ở tầng Service:**
```
Khi order.status → COMPLETED:  customers.debt_balance += order.debt_amount
Khi order.status → CANCELLED:  customers.debt_balance -= order.debt_amount  (nếu đã COMPLETED)
Khi payment ghi nhận cho KH:   customers.debt_balance -= payment.amount
Tương tự cho suppliers qua purchase_orders
Khi return_order COMPLETED:    customers.debt_balance -= return_order.total_refund (nếu hoàn tiền vào nợ)
```

---

### 🔴 Vấn đề 2 — `units` không có `store_id` — dữ liệu dùng chung toàn hệ thống (Tiêu chí 9)

**Mô tả:** Bảng `units` (đơn vị tính) không có `store_id` — được thiết kế như global lookup table. Điều này tạo ra rủi ro trong môi trường multi-tenant: một tenant xoá hoặc sửa đơn vị sẽ ảnh hưởng đến tất cả tenant khác.

**Hậu quả nếu không sửa:**
- Store A xoá unit "Kg" vì không dùng → Store B mất đơn vị tính cho toàn bộ sản phẩm đang active
- Không thể cho phép tenant tự tạo đơn vị riêng (VD: "thùng 24 lon" cho ngành nước ngọt)
- Cần quyền SUPER_ADMIN để thêm đơn vị mới — bottleneck cho onboarding

**Cách sửa:** Hai phương án:

```sql
-- Phương án A: Thêm store_id vào units (units trở thành per-tenant)
ALTER TABLE units ADD COLUMN store_id BIGINT REFERENCES stores(id);
-- NULL = unit hệ thống (không sửa/xoá được), NOT NULL = unit riêng của store

-- Phương án B: Tách thành system_units (global) và store_units (per-tenant)
-- Đơn giản hơn cho permission model nhưng phức tạp hơn khi JOIN
```

---

### 🟡 Vấn đề 3 — Thiếu `store_id` trên `price_history` và `return_order_items` (Tiêu chí 5 & 8)

**Mô tả:** Hai bảng `price_history` và `return_order_items` không có `store_id` trực tiếp. Mọi query theo store phải JOIN qua bảng cha (`products`, `return_orders`), không dùng được composite index on `store_id`.

**Hậu quả nếu không sửa:**
- Query "lịch sử thay đổi giá theo store trong tháng 5" phải JOIN `price_history → products WHERE products.store_id = ?`
- Khi `price_history` lớn (1M+ rows), JOIN này trở thành bottleneck
- Không thể partition `price_history` theo `store_id` trong tương lai

**Cách sửa:**
```sql
ALTER TABLE price_history      ADD COLUMN store_id BIGINT NOT NULL REFERENCES stores(id);
ALTER TABLE return_order_items ADD COLUMN store_id BIGINT NOT NULL REFERENCES stores(id);

CREATE INDEX idx_price_history_store_created ON price_history (store_id, changed_at DESC);
```

---

## 3. Điểm mạnh đáng giữ lại

- **Multi-tenant chuẩn:** `store_id` trên tất cả bảng nghiệp vụ — cách ly data, dễ kiểm soát quyền truy cập, sẵn sàng Row-Level Security nếu cần
- **TIMESTAMPTZ toàn bộ:** Lưu UTC, không bao giờ bị bug timezone khi deploy lên cloud đa region
- **NUMERIC(15,2) cho tiền:** Đúng chuẩn kế toán, không có lỗi làm tròn floating-point
- **Soft delete + partial UNIQUE:** `UNIQUE(store_id, sku) WHERE deleted_at IS NULL` — cho phép tạo lại sau khi xoá, không conflict
- **Immutable financial tables:** Orders/payments/inventory_transactions không thể xoá — thiết kế đúng cho hệ thống tài chính
- **Polymorphic FK đã được fix:** `payments` dùng `customer_id` + `supplier_id` tách biệt với CHECK constraint — không còn orphan risk
- **`unit_price` snapshot:** `order_items.unit_price` lưu giá tại thời điểm bán — báo cáo lợi nhuận không bị ảnh hưởng khi giá thay đổi
- **`price_history` table:** Lịch sử thay đổi giá cho phép tính margin chính xác theo thời điểm nhập hàng
- **`return_orders` table:** Core flow POS không thể thiếu, đã có từ đầu thay vì hack bằng đơn âm
- **`audit_logs` với JSONB:** old_data/new_data lưu snapshot — đủ để reconstruct mọi thay đổi, có ip_address cho forensics
- **Materialized views:** `mv_monthly_revenue` + `mv_inventory_summary` tách biệt read/write path cho dashboard — đúng hướng cho scale
- **35+ indexes:** Bao phủ đủ FK columns, composite queries, partial indexes cho soft delete và debt filter
- **`auth_profiles` tách khỏi business logic:** `users` chỉ chứa authentication, role nằm trong context (`store_members.role`, `admin_profiles.system_role`) — clean separation

---

## 4. Gợi ý mở rộng

### 1. Thêm `store_id` vào `units` + bảo vệ system units

```sql
ALTER TABLE units ADD COLUMN store_id BIGINT REFERENCES stores(id);
-- store_id NULL = system unit (không sửa/xoá được)
-- store_id NOT NULL = unit riêng của store

ALTER TABLE units ADD CONSTRAINT chk_units_name_unique
  UNIQUE (name, store_id);  -- cho phép store A và store B đều có unit "Thùng"
```

**Lý do:** Multi-tenant thực sự cần tenant tự quản lý đơn vị riêng. Ngành thực phẩm có "lốc 6 lon", ngành dược có "vỉ 10 viên" — không thể dùng chung.

### 2. Thêm `discount_type` vào `orders` và `order_items`

```sql
ALTER TABLE orders      ADD COLUMN discount_type VARCHAR(20) DEFAULT 'FIXED';
ALTER TABLE order_items ADD COLUMN discount_type VARCHAR(20) DEFAULT 'FIXED';
-- CHECK: discount_type IN ('FIXED', 'PERCENTAGE')
```

**Lý do:** Hiện tại `discount` chỉ là giá trị tuyệt đối. Thực tế POS hay dùng "giảm 10%" — cần lưu type để tính lại và hiển thị đúng trên hóa đơn.

### 3. Bảng `store_settings` — Cấu hình riêng của từng store

```sql
CREATE TABLE store_settings (
  store_id     BIGINT  PK  FK → stores,
  tax_rate     NUMERIC(5,4) DEFAULT 0,         -- 0.1 = 10% VAT
  currency     VARCHAR(10) DEFAULT 'VND',
  timezone     VARCHAR(50) DEFAULT 'Asia/Ho_Chi_Minh',
  invoice_prefix VARCHAR(10) DEFAULT 'DH',
  updated_at   TIMESTAMPTZ NOT NULL
);
```

**Lý do:** `tax` hiện được nhập thủ công từng đơn hàng — cần config mặc định để không phải nhập lại. Prefix mã đơn hàng (DH-001 vs ORD-001) cũng nên là config, không hardcode.

### 4. Cột `search_vector` trên `products` và `customers` — Full-text search

```sql
ALTER TABLE products  ADD COLUMN search_vector TSVECTOR;
ALTER TABLE customers ADD COLUMN search_vector TSVECTOR;

CREATE INDEX idx_products_search  ON products  USING GIN (search_vector);
CREATE INDEX idx_customers_search ON customers USING GIN (search_vector);

-- Trigger tự cập nhật search_vector khi insert/update
CREATE TRIGGER trg_products_search_vector
  BEFORE INSERT OR UPDATE ON products
  FOR EACH ROW EXECUTE FUNCTION
    tsvector_update_trigger(search_vector, 'pg_catalog.simple', name, description, sku);
```

**Lý do:** POS cần tìm kiếm sản phẩm nhanh khi nhập đơn hàng. `LIKE '%tên%'` không dùng được index — GIN index với `tsvector` cho phép search toàn văn nhanh ngay cả với 500K+ sản phẩm.

### 5. Bảng `notifications` — Thông báo nội bộ hệ thống

```sql
CREATE TABLE notifications (
  id           BIGSERIAL   PK,
  store_id     BIGINT      FK → stores, NOT NULL,
  user_id      BIGINT      FK → users,           -- null = broadcast to store
  type         VARCHAR(50) NOT NULL,              -- LOW_STOCK, DEBT_OVERDUE, SUBSCRIPTION_EXPIRING
  title        VARCHAR(200) NOT NULL,
  body         TEXT,
  is_read      BOOLEAN     NOT NULL DEFAULT false,
  created_at   TIMESTAMPTZ NOT NULL
);
```

**Lý do:** Hệ thống POS cần push notification khi tồn kho xuống dưới `min_stock_level`, khi khách hàng quá hạn công nợ, khi subscription sắp hết hạn. Lưu vào DB cho phép hiển thị notification history dù user offline.

---

## 5. Refactor priorities

### 🔴 Gấp (làm trước khi go-live)

- [ ] **Document + test `debt_balance` invariant** — viết integration test kiểm tra drift sau mỗi order/payment/return. Không cần sửa schema, cần discipline trong code và CI gate
- [ ] **Giải quyết `units` không có `store_id`** — quyết định giữa Phương án A (thêm `store_id` nullable) hoặc giữ global + chỉ SUPER_ADMIN được thêm. Phải quyết định trước khi có data thật
- [ ] **Thêm `store_id` vào `price_history`** — dễ thêm khi schema còn trống, sẽ khó migrate khi đã có data

### 🟡 Nên làm (trong sprint đầu tiên)

- [ ] **Thêm `discount_type`** vào `orders` và `order_items` — nghiệp vụ chắc chắn cần, thêm trước khi có đơn hàng thật
- [ ] **Tạo `store_settings`** — tránh hardcode tax rate và invoice prefix vào application code
- [ ] **Thêm `store_id` vào `return_order_items`** — nhất quán với pattern của các bảng item khác
- [ ] **Ghi chú invariant rõ** trong CLAUDE.md hoặc service layer: ai chịu trách nhiệm cập nhật `debt_balance`, trong transaction nào

### 🟢 Tùy chọn (roadmap sau)

- [ ] **Full-text search (`search_vector`)** — khi số lượng sản phẩm vượt vài nghìn và performance `LIKE` không đủ
- [ ] **`notifications` table** — khi có yêu cầu alert (tồn kho thấp, công nợ quá hạn, subscription hết hạn)
- [ ] **Partition bảng lớn** — khi `orders` hoặc `inventory_transactions` vượt 10M rows, xem xét partition theo `store_id` hoặc `created_at` range
- [ ] **Row-Level Security (RLS)** — khi cần enforce multi-tenant ở DB layer thay vì chỉ ở application layer. Đặc biệt hữu ích nếu có reporting tool truy cập DB trực tiếp
- [ ] **Encrypt PII columns** — `customers.phone`, `customers.email`, `users.email` — dùng `pgcrypto` hoặc application-level encryption nếu có yêu cầu compliance (PDPA/GDPR)

---

## 6. Ghi chú so sánh với Review trước (SCHEMA_REVIEW.md — 5.8/10)

| Vấn đề cũ | Trạng thái | Ghi chú |
|---|---|---|
| Không có index | ✅ Đã fix | 35+ indexes đầy đủ |
| `payments` polymorphic FK | ✅ Đã fix | `customer_id` + `supplier_id` tách biệt + CHECK |
| `inventory_transactions` polymorphic | ✅ Đã fix | `order_id` + `purchase_order_id` tách biệt |
| `debt_balance` không có sync | ⚠️ Còn tồn đọng | Schema không thể fix — cần Service layer discipline + test |
| CHECK constraints thiếu | ✅ Đã fix | Đầy đủ cho enum và giá trị số |
| `created_by` không đồng đều | ✅ Cải thiện | `categories`, `customers`, `suppliers` đã có |
| `price_history` thiếu | ✅ Đã thêm | Bảng đã có, thiếu `store_id` trực tiếp |
| `return_orders` thiếu | ✅ Đã thêm | Bảng đầy đủ |
| `audit_logs` thiếu | ✅ Đã thêm | Có JSONB + ip_address |
| `subscription_invoices` thiếu | ✅ Đã thêm | Bảng đầy đủ |
| Materialized views thiếu | ✅ Đã thêm | `mv_monthly_revenue` + `mv_inventory_summary` |
