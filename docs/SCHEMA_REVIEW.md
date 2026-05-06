# Đánh giá Schema Database — OmniFlow

> **Vai trò đánh giá:** Database Architect Senior, 10+ năm kinh nghiệm production
> **Ngày đánh giá:** 2026-05-06
> **Context:** Web app / Monolith Spring Boot / PostgreSQL / Multi-tenant POS B2B / Quy mô vừa

---

## 1. Bảng tổng hợp điểm

| # | Tiêu chí | Điểm | Nhận xét 1 dòng |
|---|---|---|---|
| 1 | Chuẩn hóa dữ liệu | 7/10 | 2NF đạt, vi phạm 3NF có chủ đích (debt_balance, total_price) — chấp nhận được cho POS |
| 2 | Khóa & ràng buộc | 5/10 | FK và UNIQUE ổn, nhưng thiếu CHECK constraint trên enum-like fields và giá trị số |
| 3 | Kiểu dữ liệu | 6/10 | NUMERIC và TIMESTAMPTZ đúng, nhưng polymorphic `reference_id` trong payments thiếu FK |
| 4 | Đặt tên | 7/10 | snake_case nhất quán, tên rõ nghĩa, nhưng `created_by` không đồng đều giữa các bảng |
| 5 | Index & hiệu năng | 3/10 | Không có index nào được định nghĩa ngoài PK/UNIQUE — nguy cơ chậm nghiêm trọng |
| 6 | Toàn vẹn dữ liệu | 5/10 | Soft delete tốt, nhưng `debt_balance` denorm không có cơ chế sync, payments thiếu FK |
| 7 | Bảo mật | 6/10 | password_hash đúng, store_id cách ly tenant tốt, nhưng thiếu gợi ý encrypt PII |
| 8 | Khả năng mở rộng | 6/10 | Multi-tenant chuẩn, BIGSERIAL an toàn, nhưng thiếu chiến lược partition bảng lớn |
| 9 | Phản ánh nghiệp vụ | 6/10 | Core flow đủ, nhưng thiếu hoàn trả hàng, lịch sử giá, loại chiết khấu |
| 10 | Khả năng bảo trì | 7/10 | Cấu trúc rõ ràng, nhưng polymorphic association khó maintain về lâu dài |

### Điểm trung bình tổng thể: **5.8 / 10**

---

## 2. Top 3 vấn đề nghiêm trọng nhất

### 🔴 Vấn đề 1 — Không có index (Tiêu chí 5)

**Mô tả:** Toàn bộ schema chỉ có index từ PK và UNIQUE. Không có index nào trên FK columns hay các cột thường xuyên dùng trong WHERE/ORDER BY.

**Hậu quả nếu không sửa:**
- Query `SELECT * FROM orders WHERE store_id = ? AND status = 'PENDING'` sẽ full scan toàn bảng khi có hàng trăm nghìn đơn hàng
- Báo cáo doanh thu theo tháng (`created_at BETWEEN ...`) sẽ cực chậm
- Hệ thống có thể trở nên unusable chỉ sau vài tháng vận hành với store lớn

**Index tối thiểu cần thêm ngay:**
```sql
-- FK columns
CREATE INDEX ON orders (store_id);
CREATE INDEX ON orders (customer_id);
CREATE INDEX ON inventory_transactions (product_id, created_at DESC);
CREATE INDEX ON products (store_id) WHERE deleted_at IS NULL;

-- Composite cho query phổ biến
CREATE INDEX ON orders (store_id, status);
CREATE INDEX ON orders (store_id, created_at DESC);
CREATE INDEX ON inventory_transactions (store_id, created_at DESC);
```

---

### 🔴 Vấn đề 2 — `payments.reference_id` polymorphic không có FK (Tiêu chí 6)

**Mô tả:** Bảng `payments` dùng `reference_id` + `type` để trỏ đến `customers` hoặc `suppliers`, nhưng không có Foreign Key — database không thể enforce toàn vẹn.

**Hậu quả nếu không sửa:**
- Có thể insert payment với `reference_id = 99999` trong khi customer/supplier đó không tồn tại
- Xoá customer (soft delete) nhưng payment vẫn trỏ vào → orphan record
- Bug khó trace vì không có DB-level constraint nào báo lỗi

**Cách sửa:** Tách thành 2 cột rõ ràng:
```sql
customer_id  BIGINT  FK → customers  (null nếu là supplier payment)
supplier_id  BIGINT  FK → suppliers  (null nếu là customer payment)
-- Thêm CHECK: (customer_id IS NOT NULL) != (supplier_id IS NOT NULL)
```

---

### 🔴 Vấn đề 3 — `debt_balance` denorm không có cơ chế sync (Tiêu chí 6)

**Mô tả:** `customers.debt_balance` và `suppliers.debt_balance` là giá trị tổng hợp từ orders/purchase_orders, nhưng không có trigger hay constraint nào đảm bảo đồng bộ.

**Hậu quả nếu không sửa:**
- Bug ở tầng application (quên cập nhật balance) dẫn đến số liệu công nợ sai
- Khách hàng nợ 5 triệu nhưng hệ thống hiển thị 0 — mất tiền thật
- Rất khó phát hiện vì không có validation nào ở DB layer

**Cách xử lý:** Bắt buộc cập nhật `debt_balance` trong cùng 1 transaction khi tạo/huỷ order. Document rõ rule này và viết test kiểm tra invariant.

**Invariant rules (bắt buộc enforce ở tầng Service):**

```
-- Khi tạo order COMPLETED với debt_amount > 0:
customers.debt_balance += order.debt_amount

-- Khi order bị CANCELLED (đã từng COMPLETED):
customers.debt_balance -= order.debt_amount

-- Khi ghi nhận payment cho customer:
customers.debt_balance -= payment.amount

-- Tương tự cho suppliers qua purchase_orders và payments
```

**Invariant check (viết integration test):**
```sql
-- Kết quả phải = 0 cho tất cả customers
SELECT c.id, c.debt_balance - COALESCE(SUM(o.debt_amount), 0) + COALESCE(SUM(p.amount), 0) AS drift
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id AND o.status = 'COMPLETED'
LEFT JOIN payments p ON p.customer_id = c.id
GROUP BY c.id, c.debt_balance
HAVING ABS(c.debt_balance - COALESCE(SUM(o.debt_amount), 0) + COALESCE(SUM(p.amount), 0)) > 0.01;
```

---

## 3. Điểm mạnh đáng giữ lại

- **Multi-tenant chuẩn:** `store_id` trên mọi bảng nghiệp vụ — cách ly data chắc chắn, dễ kiểm soát quyền truy cập
- **TIMESTAMPTZ:** Lưu UTC, tránh bug timezone khi deploy lên cloud khác region
- **NUMERIC(15,2) cho tiền:** Đúng đắn, tránh lỗi làm tròn floating-point trong tính toán tài chính
- **Soft delete với partial UNIQUE:** `UNIQUE(store_id, sku) WHERE deleted_at IS NULL` — cho phép tạo lại sau khi xoá mà không bị conflict
- **Bảo vệ dữ liệu tài chính:** Không cho xoá orders/payments/inventory_transactions — thiết kế đúng cho hệ thống kế toán
- **Tách `users` khỏi business logic:** Dễ thêm OAuth/2FA sau này mà không ảnh hưởng schema nghiệp vụ
- **`unit_price` snapshot trong `order_items`:** Lưu giá tại thời điểm bán, không bị ảnh hưởng khi giá sản phẩm thay đổi sau này

---

## 4. Gợi ý mở rộng

### 1. Bảng `price_history` — Lịch sử thay đổi giá sản phẩm
```
product_id, old_cost_price, new_cost_price, old_selling_price, new_selling_price, changed_by, changed_at
```
**Lý do:** Khi giá nhập thay đổi, báo cáo lợi nhuận của đơn hàng cũ sẽ sai nếu chỉ lấy `products.cost_price` hiện tại. Cần lịch sử để tính đúng margin.

### 2. Bảng `return_orders` — Hoàn trả hàng
```
store_id, original_order_id, reason, status, created_by, created_at
return_order_items: product_id, quantity, refund_amount
```
**Lý do:** Thực tế POS luôn có đổi trả — thiếu bảng này buộc dùng hack như tạo đơn âm, gây sai lệch báo cáo tồn kho và doanh thu.

### 3. Bảng `audit_logs` — Nhật ký thao tác
```
table_name, record_id, action (CREATE/UPDATE/DELETE), old_data JSONB, new_data JSONB, performed_by, performed_at
```
**Lý do:** Hệ thống tài chính cần biết ai sửa gì lúc mấy giờ. Đặc biệt quan trọng khi có tranh chấp hoặc kiểm toán.

### 4. Bảng `subscription_invoices` — Lịch sử thanh toán subscription
```
store_id, plan, amount, billing_cycle, paid_at, payment_method, status
```
**Lý do:** `subscriptions` hiện chỉ lưu trạng thái hiện tại — không biết store đã trả tiền bao nhiêu lần, trả qua kênh nào, có bao giờ trễ hạn không.

### 5. CHECK constraints trên enum-like columns
```sql
ALTER TABLE orders ADD CONSTRAINT chk_orders_status
  CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'));

ALTER TABLE products ADD CONSTRAINT chk_products_price
  CHECK (selling_price >= 0 AND cost_price >= 0);

ALTER TABLE inventory ADD CONSTRAINT chk_inventory_quantity
  CHECK (quantity >= 0);
```
**Lý do:** Hiện tại không có gì ngăn insert `status = 'INVALID_VALUE'` hay `selling_price = -1` vào DB.

---

## 5. Refactor priorities

### 🔴 Gấp (làm trước khi go-live)
- [ ] Thêm index trên FK columns và composite index cho query phổ biến
- [ ] Sửa `payments` — tách `reference_id` thành `customer_id` + `supplier_id` có FK
- [ ] Thêm CHECK constraints trên status/role/type columns và giá trị số âm

### 🟡 Nên làm (trong sprint đầu tiên)
- [ ] Thêm `price_history` table — cần thiết ngay khi có tính năng sửa giá sản phẩm
- [ ] Đồng bộ `created_by` nhất quán — một số bảng có, một số không (categories, customers, suppliers thiếu)
- [ ] Document rõ invariant của `debt_balance` và viết integration test kiểm tra

### 🟢 Tùy chọn (roadmap sau)
- [ ] Thêm `return_orders` khi có yêu cầu nghiệp vụ hoàn trả
- [ ] Thêm `audit_logs` khi có yêu cầu compliance/kiểm toán
- [ ] Thêm `subscription_invoices` khi monetization đi vào hoạt động
- [ ] Xem xét partition bảng `orders` và `inventory_transactions` theo `store_id` hoặc `created_at` khi data vượt 10 triệu rows
