# Schema Review 1.1 — OmniFlow

> **Vai trò đánh giá:** Database Architect Senior, 10+ năm kinh nghiệm production
> **Ngày đánh giá:** 2026-05-07
> **Phiên bản schema:** 1.1 (bổ sung cột local-first + sync_change_log)
> **Context:** Web app / Monolith Spring Boot / PostgreSQL / Multi-tenant POS B2B / Quy mô vừa

---

## 1. Bảng tổng hợp điểm

| # | Tiêu chí | Điểm | Nhận xét 1 dòng |
|---|---|---|---|
| 1 | Chuẩn hóa dữ liệu | 8/10 | 3NF tốt; denormalized (`debt_balance`, `total_price`) có giải thích rõ ràng |
| 2 | Khóa & ràng buộc | 8/10 | FK và UNIQUE partial đầy đủ; CHECK constraints bao phủ enum và số |
| 3 | Kiểu dữ liệu | 8/10 | NUMERIC cho tiền, TIMESTAMPTZ cho thời gian, JSONB cho audit — hợp lý |
| 4 | Đặt tên | 7/10 | Nhất quán snake_case; thêm cột sync chuẩn rõ nghĩa |
| 5 | Index & hiệu năng | 8/10 | Index FK + composite tốt; chưa có full-text search cho tìm kiếm nhanh |
| 6 | Toàn vẹn dữ liệu | 7/10 | Soft delete ổn; `debt_balance` vẫn cần kỷ luật app/test |
| 7 | Bảo mật | 6/10 | Có password_hash và tenant isolation; thiếu gợi ý encrypt PII |
| 8 | Khả năng mở rộng | 7/10 | Multi-tenant rõ ràng; chưa có partition cho bảng lớn |
| 9 | Phản ánh nghiệp vụ | 8/10 | Flow POS đủ: bán, nhập, công nợ, hoàn trả, kho, audit |
| 10 | Khả năng bảo trì | 7/10 | Schema rõ ràng; thêm sync fields tăng coupling cần convention chặt |

### Điểm trung bình tổng thể: **7.4 / 10**

> **Ghi chú 1.1:** Đã bổ sung cột local-first (public_id, sync_version, last_modified_*) và `sync_change_log`, giúp sẵn sàng sync tốt hơn, nhưng cần ràng buộc quy ước cập nhật ở service layer.

---

## 2. Top 3 vấn đề nghiêm trọng nhất

### 🔴 Vấn đề 1 — `debt_balance` không có enforcement DB (Tiêu chí 6)

**Mô tả:** `customers.debt_balance` và `suppliers.debt_balance` là số dư denormalized, không có trigger/constraint bảo đảm đồng bộ với orders/payments.

**Hậu quả nếu không sửa:**
- Drift công nợ khi có bug hoặc race condition → sai số tài chính thực
- Khó truy vết khi phát sinh tranh chấp

**Khuyến nghị:** giữ invariant query + integration test bắt buộc, update trong cùng transaction ở Service.

---

### 🔴 Vấn đề 2 — Bổ sung nhiều cột sync nhưng chưa có quy ước cập nhật (Tiêu chí 10)

**Mô tả:** `public_id`, `sync_version`, `last_modified_*` đã có trên nhiều bảng mutable nhưng chưa có quy ước rõ ràng về tăng version và ghi log.

**Hậu quả nếu không sửa:**
- Dữ liệu sync delta sai thứ tự
- Conflict khó phát hiện, lỗi silent

**Khuyến nghị:** chuẩn hóa “sync write contract” (khi nào bump version, ai ghi `sync_change_log`, khi nào set `last_modified_by_device`).

---

### 🟡 Vấn đề 3 — Thiếu full-text search cho sản phẩm/khách hàng (Tiêu chí 5)

**Mô tả:** POS thường cần search nhanh sản phẩm và khách hàng. Hiện chỉ có index thường, thiếu `tsvector`.

**Hậu quả nếu không sửa:**
- Query LIKE '%term%' không dùng index → chậm khi data lớn

**Khuyến nghị:** thêm `search_vector` + GIN index cho `products`, `customers`.

---

## 3. Điểm mạnh đáng giữ lại

- **Multi-tenant chuẩn:** `store_id` rõ ràng, dễ kiểm soát quyền truy cập
- **TIMESTAMPTZ toàn bộ:** an toàn timezone
- **NUMERIC(15,2) cho tiền:** đúng chuẩn kế toán
- **Soft delete + partial UNIQUE:** tránh conflict khi recreate
- **Immutable financial tables:** giữ lịch sử tài chính đúng chuẩn
- **Audit logs đầy đủ:** JSONB + ip_address phục vụ forensic
- **Materialized views:** tách read/write cho dashboard
- **Local-first fields:** `public_id`, `sync_version`, `last_modified_*` chuẩn bị cho sync engine
- **sync_change_log:** nền tảng cho delta sync

---

## 4. Gợi ý mở rộng (3–5 hướng)

1. **Full-text search** cho `products`, `customers` (GIN + tsvector) để tìm kiếm nhanh khi data lớn.
2. **Bảng `store_settings`** lưu tax rate, prefix hóa đơn, timezone — giảm hardcode ở app.
3. **`discount_type`** cho `orders` và `order_items` để hỗ trợ giảm theo %.
4. **Partition bảng lớn** (`orders`, `inventory_transactions`) theo `store_id` hoặc `created_at` khi vượt 10M rows.

---

## 5. Refactor priorities

### 🔴 Gấp (trước go-live)
- [ ] Chuẩn hóa **sync write contract**: tăng `sync_version`, ghi `sync_change_log`, set `last_modified_*`.
- [ ] Bắt buộc **integration test** cho invariant `debt_balance`.
- [ ] Xác định **source-of-truth** cho `inventory.quantity` (event-sourcing hay snapshot).

### 🟡 Nên làm (sprint đầu)
- [ ] Thêm `discount_type` cho orders/order_items.
- [ ] Tạo `store_settings`.
- [ ] Tối ưu search bằng GIN + tsvector.

### 🟢 Tùy chọn (roadmap)
- [ ] Partition bảng lớn theo tenant/time.
- [ ] Row-Level Security (RLS) nếu có BI/reporting truy cập DB trực tiếp.
- [ ] Encrypt PII (phone/email) theo PDPA/GDPR.

