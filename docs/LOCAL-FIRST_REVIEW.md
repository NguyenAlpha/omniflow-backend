# Đánh giá local-first / offline-first cho schema OmniFlow

## Bối cảnh & giả định
- Mục tiêu sync: multi-device (một cửa hàng dùng nhiều thiết bị)
- Conflict tolerance: ưu tiên phát hiện conflict; một số bảng có thể LWW, một số cần merge thủ công
- Bảng cần offline nhất: products, inventory, orders, order_items, customers, suppliers, payments, inventory_transactions

> Nếu giả định này chưa đúng, vui lòng cung cấp lại để chấm điểm chính xác hơn.

---

## 1. Bảng điểm

| Nhóm | Điểm /10 | Vấn đề chính (1 dòng) |
|---|---:|---|
| S1. Timestamp & versioning | 6 | Có created_at/updated_at nhưng thiếu sync_version/sequence chung cho đồng bộ |
| S2. Soft delete readiness | 7 | Nhiều bảng có deleted_at; nhưng các bảng con/append-only vẫn thiếu tombstone khi cần remove |
| S3. Conflict detection | 4 | PK BIGSERIAL, thiếu device_id/client_id, thiếu row_version để phát hiện conflict |
| S4. Change tracking | 3 | Chưa có change_log/sync_queue; khó sync delta và xử lý thứ tự FK offline |
| S5. Sync scope & performance | 6 | Hầu hết có store_id, nhưng thiếu store_id ở một số bảng con gây sync nặng |

## 2. Điểm sync-readiness tổng thể
**5.2 / 10**

---

## 3. Danh sách thay đổi schema cần thiết

### Bắt buộc (thiếu thì sync không hoạt động được)
1. **Thêm khóa sync ổn định (UUID) hoặc chuyển PK sang UUID** cho các bảng mutable (products, customers, suppliers, orders, order_items, inventory, warehouses, categories, units, store_members, return_orders, purchase_orders, payments).
2. **Thêm sync_version / sequence_number** (BIGINT) cho mọi bảng cần sync để client pull delta theo thứ tự.
3. **Thêm device_id/client_id và last_modified_by** để phát hiện xung đột khi nhiều thiết bị chỉnh cùng lúc.
4. **Bổ sung cơ chế change tracking**: bảng `sync_change_log` hoặc `sync_queue` ghi lại thay đổi theo thứ tự.

### Nên có (thiếu thì sync không ổn định)
1. **Bổ sung deleted_at** cho các bảng có thể bị xóa hoặc ẩn khỏi client (order_items, purchase_order_items, inventory, inventory_transactions nếu có chỉnh sửa sai).
2. **Thêm store_id vào bảng con** (order_items, purchase_order_items, inventory, price_history, return_order_items) để filter theo tenant mà không cần join lớn.
3. **Chuẩn hóa updated_at cho bảng append-only** (payments, inventory_transactions, audit_logs, subscription_invoices) hoặc đánh dấu bất biến rõ ràng trong sync logic.

### Tùy chọn (cải thiện hiệu năng / UX)
1. **Thêm bảng device_registry** để quản lý thiết bị, last_seen, user mapping.
2. **Thêm snapshot/hash** (row_hash) để phát hiện drift nhẹ và tối ưu payload.
3. **Partition theo store_id hoặc theo thời gian** cho các bảng giao dịch lớn (orders, payments, inventory_transactions).

---

## 4. Migration SQL cụ thể (PostgreSQL)

> Các DDL dưới đây là mẫu tối thiểu. Áp dụng cho từng bảng cần sync.

### 4.1. Thêm UUID và version
```sql
-- Ví dụ: products
ALTER TABLE products
  ADD COLUMN public_id UUID DEFAULT gen_random_uuid() NOT NULL,
  ADD COLUMN sync_version BIGINT DEFAULT 0 NOT NULL,
  ADD COLUMN last_modified_by UUID,
  ADD COLUMN last_modified_at TIMESTAMPTZ DEFAULT now() NOT NULL;

CREATE UNIQUE INDEX ux_products_public_id ON products (public_id);
CREATE INDEX idx_products_sync_version ON products (sync_version);
```

### 4.2. Thêm device_registry
```sql
CREATE TABLE device_registry (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id BIGINT NOT NULL REFERENCES users(id),
  device_name VARCHAR(100),
  platform VARCHAR(50),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_registry_user_id ON device_registry (user_id);
```

### 4.3. Bảng change_log (delta sync)
```sql
CREATE TABLE sync_change_log (
  id BIGSERIAL PRIMARY KEY,
  store_id BIGINT NOT NULL REFERENCES stores(id),
  table_name VARCHAR(50) NOT NULL,
  record_public_id UUID NOT NULL,
  operation VARCHAR(10) NOT NULL CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE')),
  sync_version BIGINT NOT NULL,
  changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  changed_by_device UUID
);

CREATE INDEX idx_sync_change_log_store_version
  ON sync_change_log (store_id, sync_version);
```

### 4.4. Thêm store_id cho bảng con (ví dụ order_items)
```sql
ALTER TABLE order_items
  ADD COLUMN store_id BIGINT;

UPDATE order_items oi
SET store_id = o.store_id
FROM orders o
WHERE oi.order_id = o.id AND oi.store_id IS NULL;

ALTER TABLE order_items
  ALTER COLUMN store_id SET NOT NULL,
  ADD CONSTRAINT fk_order_items_store
    FOREIGN KEY (store_id) REFERENCES stores(id);

CREATE INDEX idx_order_items_store_id ON order_items (store_id);
```

### 4.5. Thêm deleted_at cho bảng con (nếu cần xóa mềm)
```sql
ALTER TABLE order_items
  ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_order_items_deleted_at
  ON order_items (order_id) WHERE deleted_at IS NULL;
```

---

## 5. Cảnh báo conflict risk

1. **inventory / inventory_transactions**
   - Rủi ro: hai thiết bị cùng điều chỉnh tồn kho offline → số lượng lệch.
   - Gợi ý: chuyển thành event-sourcing (append-only) + reconcile ở server; tránh sửa trực tiếp `inventory.quantity` từ client.

2. **orders + order_items + payments**
   - Rủi ro: tạo đơn, cập nhật thanh toán, hoàn trả trên nhiều thiết bị.
   - Gợi ý: dùng optimistic concurrency (version), khóa theo order_code, hoặc yêu cầu merge thủ công nếu cùng edit.

3. **customers/suppliers.debt_balance (denormalized)**
   - Rủi ro: drift khi offline update và sync trễ.
   - Gợi ý: tính lại từ event log khi conflict; chỉ sync events (orders, payments) thay vì số dư.

4. **products / price_history**
   - Rủi ro: hai thiết bị đổi giá song song.
   - Gợi ý: LWW cho product, nhưng price_history luôn append; nếu conflict, giữ cả hai giá và flag cần review.

---

## Kết luận ngắn
Schema hiện tại khá tốt cho mô hình online-first, nhưng để sẵn sàng local-first cần thêm **UUID sync key**, **sync_version**, **change_log**, và **device tracking**. Nếu áp dụng các thay đổi bắt buộc ở trên, hệ thống sẽ đủ nền tảng để sync ổn định trong môi trường nhiều thiết bị offline.

