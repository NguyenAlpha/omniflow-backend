# Entity Model

26 JPA entities, nhóm theo domain. Mọi entity editable đều có sync fields.

---

## 1. Sync Fields (có trên mọi entity editable)

| Field | Kiểu | Mô tả |
|:---|:---|:---|
| `publicId` | UUID | ID public dùng cho API và mobile sync — không dùng internal `id` |
| `syncVersion` | BIGINT | Tăng mỗi khi entity thay đổi — dùng cho delta sync mobile |
| `lastModifiedAt` | TIMESTAMPTZ | Thời điểm thay đổi gần nhất |
| `lastModifiedByUser` | FK → User | User thực hiện thay đổi |
| `lastModifiedByDevice` | String | Device ID (mobile sync) |

---

## 2. Xác thực & Phân quyền

| Entity | Mô tả |
|:---|:---|
| `User` | Implements `UserDetails`; có `passwordHash`, `isActive`, soft delete (`deletedAt`) |
| `Role` | Backed by enum `RoleName`; bảng tra cứu tĩnh |
| `UserRole` | RBAC pivot: `store_id IS NULL` → global role; `store_id NOT NULL` → store-scoped |

**RoleName:**

| Value | Scope | Mô tả |
|:---|:---|:---|
| `SUPER_ADMIN` | Global | Toàn quyền, bypass mọi store check |
| `SUPPORT` | Global | Hỗ trợ hệ thống |
| `OWNER` | Store-scoped | Chủ cửa hàng — toàn quyền trong store |
| `MANAGER` | Store-scoped | Quản lý — CRUD sản phẩm, danh mục, đơn hàng |
| `STAFF` | Store-scoped | Nhân viên — read + tạo đơn hàng |

> Store-scoped roles **không** nhúng vào JWT — kiểm tra qua Redis/DB theo từng request.
> Xem chi tiết: [SECURITY.md](SECURITY.md), [STORE_MEMBER_LIFECYCLE.md](STORE_MEMBER_LIFECYCLE.md)

---

## 3. Tenant & Membership

| Entity | Mô tả |
|:---|:---|
| `Store` | Multi-tenant root — mọi dữ liệu nghiệp vụ gắn `store_id` |
| `StoreMember` | Metadata thành viên: `positionTitle`, `joinedDate`, sync fields |
| `Subscription` | Gói dịch vụ: `FREE / BASIC / PRO`; giới hạn staff/product/warehouse/orders |
| `SubscriptionInvoice` | Lịch sử billing — immutable, không xóa |

---

## 4. Danh mục & Kho

| Entity | Mô tả |
|:---|:---|
| `Category` | Per-store; unique `(store_id, name)` |
| `Product` | `sku` unique per store; `searchVector` TSVECTOR cho full-text search |
| `Unit` | `store_id IS NULL` = system unit (SUPER_ADMIN quản lý); `NOT NULL` = store custom |
| `PriceHistory` | Append-only khi giá thay đổi — không xóa, không sửa |
| `Warehouse` | Multi-warehouse per store |
| `Inventory` | Tồn kho theo `(product, warehouse)` |
| `InventoryTransaction` | Loại: `IN / OUT / TRANSFER / ADJUSTMENT` — immutable |

---

## 5. Đối tác

| Entity | Mô tả |
|:---|:---|
| `Customer` | `debtBalance` denormalized; `searchVector` cho full-text |
| `Supplier` | `debtBalance` denormalized |

---

## 6. Đơn hàng & Mua hàng

| Entity | Mô tả |
|:---|:---|
| `Order` | Bán hàng; `discountType: FIXED / PERCENT`; status: `PENDING / COMPLETED / CANCELLED` |
| `OrderItem` | Line items với `unitPrice` snapshot + discount per item |
| `ReturnOrder` | Liên kết `originalOrderId`; `refundMethod: CASH / BANK_TRANSFER / STORE_CREDIT` |
| `ReturnOrderItem` | Line items của return |
| `PurchaseOrder` | Nhập hàng từ supplier; status: `PENDING / RECEIVED / CANCELLED` |
| `PurchaseOrderItem` | Line items của purchase |
| `Payment` | Thu/chi; CHECK constraint: chỉ thuộc customer hoặc supplier, không cả hai |

---

## 7. Audit & Sync

| Entity | Mô tả |
|:---|:---|
| `AuditLog` | `old_data / new_data` JSONB; immutable — không xóa |
| `SyncChangeLog` | Delta sync cho mobile: `(store_id, table, public_id, operation, sync_version)` |

---

## 8. DB Conventions

### Migrations
- Chỉ dùng Flyway: `src/main/resources/db/migration/V{n}__{mô_tả}.sql`
- `ddl-auto=validate` — Hibernate chỉ kiểm tra schema, không tự tạo/sửa
- Không dùng `ddl-auto=create` hay `update` trong bất kỳ môi trường nào

### Soft delete
- Mọi entity xóa được đều có cột `deleted_at TIMESTAMPTZ NULL`
- Query phải luôn filter `AND deleted_at IS NULL`
- Dùng suffix `AndDeletedAtIsNull` trên repository method — không bỏ sót

### Immutable tables
Các bảng sau **không xóa, không sửa nội dung** — chỉ thay đổi `status`:

```
orders, purchase_orders, payments,
inventory_transactions, price_history,
audit_logs, subscription_invoices
```

### Units query
Unit có 2 loại: system (`store_id IS NULL`) và store custom (`store_id NOT NULL`).
Query phải lấy cả hai:

```sql
WHERE (store_id = :storeId OR store_id IS NULL) AND deleted_at IS NULL
```

### `debt_balance` — invariant bắt buộc
`customers.debt_balance` và `suppliers.debt_balance` là giá trị denormalized.
**Phải cập nhật trong cùng `@Transactional`** với sự kiện gây ra thay đổi:
- Order `COMPLETED` → cộng vào `customer.debtBalance`
- Payment recorded → trừ khỏi `customer.debtBalance` hoặc `supplier.debtBalance`
- Return `COMPLETED` → hoàn lại

Không được để hai bảng lệch nhau giữa các transaction.

### Sync write contract
Mỗi mutation (INSERT / UPDATE) phải:
1. Tăng `sync_version` của entity (atomic)
2. Ghi 1 dòng vào `sync_change_log` với `(store_id, table, public_id, operation, sync_version)`

Hai bước này phải trong cùng `@Transactional`.
