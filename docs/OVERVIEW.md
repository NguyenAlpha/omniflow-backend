# Project OmniFlow — POS & Inventory System

## 1. Tổng quan dự án

**OmniFlow** là hệ thống quản lý bán hàng và kho vận đa tenant (multi-tenant B2B SaaS), tập trung vào tính chính xác của dữ liệu tài chính, phân quyền theo vai trò, và khả năng mở rộng cho App Mobile.

- **Tên dự án:** OmniFlow
- **Mô hình:** Multi-tenant B2B SaaS — 1 hệ thống phục vụ nhiều chủ cửa hàng, dữ liệu tách biệt hoàn toàn theo `store_id`
- **Trạng thái:** Phase 1 — Core API + Database Schema

---

## 2. Tech Stack

| Thành phần | Công nghệ | Lý do |
|:---|:---|:---|
| **Backend** | Java 21 / Spring Boot 3.x | Ổn định, bảo mật, virtual threads sẵn sàng |
| **Database** | PostgreSQL 16 | TIMESTAMPTZ, JSONB, materialized views, partial index; dùng thêm GIN/tsvector cho full-text và extension `pgcrypto` / `uuid-ossp` cho UUID nếu cần |
| **ORM** | Spring Data JPA + Hibernate 6 | `@EntityGraph`, `JOIN FETCH`, `@JdbcTypeCode` cho JSONB |
| **Migration** | Flyway | Schema versioning, không dùng `ddl-auto=create/update` |
| **Auth** | Spring Security + JWT (JJWT) | Stateless, encode `storeId` + `role` vào token |
| **Web Admin** | React.js / Tailwind CSS | Dashboard quản lý (Phase 1) |
| **Mobile** | React Native (Phase 2) | Android/iOS, tái dụng logic từ Web |
| **Offline / Sync** | SQLite (Mobile) / H2 (Desktop) + sync engine | Lưu tạm khi mất kết nối; server side uses `public_id` (UUID), `sync_version`, `sync_change_log` to reconcile deltas |

---

## 3. Cấu trúc Package

- **Group ID:** `com.omniflow`
- **Artifact ID:** `omniflow-backend`
- **Base Package:** `com.omniflow.backend`

```
com.omniflow.backend
├── controller/     — REST endpoints (@RestController)
├── service/        — Business logic (@Service, @Transactional)
├── repository/     — JPA repositories (extends JpaRepository)
├── entity/         — JPA entities (@Entity)
├── dto/            — Request/Response DTOs
│   ├── request/
│   └── response/
├── config/         — Spring config (Security, Cache, JWT filter)
├── event/          — Domain events (OrderCompletedEvent, ...)
└── exception/      — Custom exceptions + GlobalExceptionHandler
```

---

## 4. Các Module chức năng

### 4.1 Xác thực & Phân quyền
- Đăng ký / đăng nhập — trả về JWT encode `userId` + `storeId` + `role`
- RBAC: `OWNER` / `MANAGER` / `STAFF` (trong store) và `SUPER_ADMIN` / `SUPPORT` (system)
- 1 user có thể là thành viên của nhiều store với role khác nhau
- Admin system quản lý qua `admin_profiles` — tách biệt hoàn toàn với store logic

### 4.2 Quản lý Cửa hàng & Subscription
- Tạo store → tự động tạo `store_members` với role `OWNER` + `subscriptions` gói FREE
- Gói: `FREE` / `BASIC` / `PRO` — giới hạn số nhân viên, sản phẩm, kho, đơn hàng/tháng
- Lịch sử thanh toán subscription lưu trong `subscription_invoices`

### 4.3 Quản lý Danh mục & Đơn vị tính
- Danh mục sản phẩm per-store (`categories`)
- Đơn vị tính (`units`): system units (NULL store_id, SUPER_ADMIN quản lý) + custom units per-store
- Query units: `WHERE (store_id = :storeId OR store_id IS NULL) AND deleted_at IS NULL`

### 4.4 Quản lý Sản phẩm
- SKU unique theo store (partial UNIQUE `WHERE deleted_at IS NULL`)
- Lịch sử thay đổi giá (`price_history`) — bắt buộc ghi khi sửa giá để tính margin đúng
- Soft delete — không xoá vật lý
- Thêm hỗ trợ tìm kiếm nhanh: `search_vector` (tsvector) trên `products` và `customers` với GIN index; backend tạo/maintain trigger (`tsvector_update_trigger`) hoặc cập nhật trường này khi tên/sku/description thay đổi — giúp search tốc độ cao cho POS

### 4.5 Quản lý Kho
- Multi-warehouse per store (`warehouses`)
- Tồn kho theo cặp `(product, warehouse)` — bảng `inventory`
- Mọi biến động tồn kho ghi vào `inventory_transactions` (immutable)
- Loại giao dịch: `IN` / `OUT` / `TRANSFER` / `ADJUSTMENT`
- Cảnh báo tồn kho thấp qua `min_stock_level` và materialized view `mv_inventory_summary`

### 4.6 Quản lý Đơn hàng bán
- Tạo đơn → tính `subtotal`, `discount`, `discount_type`, `tax`, `total_amount`, `debt_amount`
- `discount_type` hỗ trợ:
  - `FIXED`: `total_amount = subtotal - discount + tax`
  - `PERCENT`: `total_amount = subtotal - (subtotal * discount / 100) + tax`
- Trạng thái: `PENDING` → `COMPLETED` / `CANCELLED` (không xoá)
- Khi `COMPLETED`: cập nhật tồn kho (OUT) + `customers.debt_balance` trong cùng 1 transaction
- `order_items` có `discount` và `discount_type` (cùng logic) để hỗ trợ giảm theo dòng

### 4.7 Hoàn trả hàng
- Đơn hoàn trả liên kết với đơn gốc (`original_order_id`)
- Khi `COMPLETED`: hoàn tồn kho (IN) + điều chỉnh `customers.debt_balance`
- Hình thức hoàn: `CASH` / `BANK_TRANSFER` / `STORE_CREDIT`

### 4.8 Quản lý Nhập hàng
- Đơn nhập từ nhà cung cấp (`purchase_orders`)
- Khi `RECEIVED`: cập nhật tồn kho (IN) + `suppliers.debt_balance`
- Trạng thái: `PENDING` → `RECEIVED` / `CANCELLED` (không xoá)

### 4.9 Quản lý Công nợ & Thanh toán
- Bảng `payments` ghi nhận thu tiền khách hoặc trả tiền nhà cung cấp
- `customers.debt_balance` và `suppliers.debt_balance` là denorm — bắt buộc cập nhật trong cùng transaction (xem `debt_balance` Invariant Rules trong DATABASE_SCHEMA.md)
- Một payment chỉ thuộc về 1 trong 2: customer hoặc supplier (CHECK constraint)

### 4.10 Báo cáo & Dashboard
- Materialized views refresh định kỳ — không aggregate trực tiếp trên bảng lớn:
  - `mv_monthly_revenue` — doanh thu theo tháng/store
  - `mv_inventory_summary` — tồn kho + cảnh báo thấp
- Báo cáo công nợ dùng `debt_balance` denorm + partial index — không cần JOIN

### 4.11 Audit Log
- Mọi thao tác nhạy cảm (sửa giá, huỷ đơn, thay đổi role, thay đổi subscription) ghi vào `audit_logs`
- Lưu `old_data` / `new_data` dạng JSONB + `ip_address` — immutable
- Ngoài ra hệ thống lưu `sync_change_log` trên server để hỗ trợ delta sync: ghi `public_id`, `operation`, `sync_version` theo `store_id` để client có thể kéo delta an toàn

---

## 5. Dependencies (pom.xml)

### Đã có
| Dependency | Dùng cho |
|:---|:---|
| `spring-boot-starter-data-jpa` | JPA/Hibernate |
| `spring-boot-starter-security` | Spring Security |
| `spring-boot-starter-validation` | Bean Validation (`@Valid`, `@NotNull`) |
| `spring-boot-starter-web` | REST API |
| `flyway-core` + `flyway-database-postgresql` | Schema migration |
| `postgresql` | JDBC driver |
| `lombok` | Boilerplate reduction |

### Cần thêm

**JWT — bắt buộc (auth chưa hoạt động nếu thiếu):**
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

**Cache — nên thêm sớm (subscription check gọi mỗi request):**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

> Caffeine là in-memory cache — không cần Redis infrastructure. Đủ cho monolith single-instance.
> Nếu sau này scale multi-instance → đổi sang Redis (`spring-boot-starter-data-redis`).

### Không cần thêm (đã được cover)

| Tính năng | Lý do không cần dep riêng |
|:---|:---|
| JSONB mapping (`audit_logs.old_data`) | Hibernate 6 hỗ trợ native qua `@JdbcTypeCode(SqlTypes.JSON)` + Jackson có sẵn từ `spring-boot-starter-web` |
| DTO mapping | Làm thủ công hoặc dùng `MapStruct` (optional) — không bắt buộc cho Phase 1 |
| Materialized view refresh | Gọi native query qua `EntityManager` — không cần dep |

---

## 6. Lộ trình phát triển (Roadmap)

| Giai đoạn | Nội dung |
|:---|:---|
| **Phase 1** | Core API (Spring Boot) + Database Schema + JWT Auth + Web Admin |
| **Phase 2** | Tích hợp ngoại vi (máy in hóa đơn, máy quét mã vạch) |
| **Phase 3** | App Mobile (React Native) + Offline sync |
| **Phase 4** | Cloud deployment (Docker + AWS/GCP) + Read replica |

---

## 7. Ghi chú quan trọng cho lập trình viên

### Database
- **Migration:** Chỉ dùng Flyway — đặt file tại `src/main/resources/db/migration/V{n}__{mô_tả}.sql`. Không dùng `ddl-auto=create/update`.
- **Thêm cột sync & migration an toàn:** nhiều bảng bổ sung `public_id UUID`, `sync_version BIGINT`, `last_modified_at`, `last_modified_by_user`, `last_modified_by_device` — khi migrate trên production lớn, thêm cột nullable / with default, backfill giá trị (gen_random_uuid()) rồi tạo unique index; tránh đặt NOT NULL không DEFAULT trực tiếp trên bảng lớn.
- **Full-text search:** tạo `search_vector` TSVECTOR và GIN index cho `products`/`customers`. Thêm trigger mẫu:

```sql
-- ví dụ trigger (Postgres):
CREATE FUNCTION products_tsvector_trigger() RETURNS trigger AS $$
begin
  new.search_vector := to_tsvector('simple', coalesce(new.name,'') || ' ' || coalesce(new.sku,'') || ' ' || coalesce(new.description,''));
  return new;
end
$$ LANGUAGE plpgsql;

CREATE TRIGGER tsvectorupdate BEFORE INSERT OR UPDATE
  ON products FOR EACH ROW EXECUTE FUNCTION products_tsvector_trigger();
```

- **Offline / sync write contract:** Service layer phải đảm bảo: tăng `sync_version` atomically, ghi một hàng vào `sync_change_log(store_id, table_name, record_public_id, operation, sync_version)` trong cùng transaction; client chỉ pull theo `(store_id, sync_version)`.
- **Soft delete:** Mọi query trên bảng có `deleted_at` phải thêm `AND deleted_at IS NULL` hoặc dùng `@Where` trên entity.
- **Immutable tables:** `orders`, `purchase_orders`, `payments`, `inventory_transactions`, `price_history`, `audit_logs` — không xoá, chỉ huỷ qua `status`.

### `debt_balance` — invariant bắt buộc
`customers.debt_balance` và `suppliers.debt_balance` là denorm — **phải cập nhật trong cùng `@Transactional`** với sự kiện gây ra thay đổi. Xem bảng invariant rules đầy đủ trong `docs/DATABASE_SCHEMA.md § 12`.

### JPA / Performance
- **N+1:** Các quan hệ `Order → items → product`, `PurchaseOrder → items → product`, `ReturnOrder → items → product` đều nguy cơ N+1. Dùng `JOIN FETCH` hoặc `@EntityGraph` — không để lazy load trong vòng lặp.
- **`units` query:** Phải lấy cả system units và store units: `WHERE (store_id = :storeId OR store_id IS NULL) AND deleted_at IS NULL`.
- **Pagination:** Dùng keyset (cursor-based) thay vì `Page<T>` OFFSET cho các bảng lớn (`orders`, `inventory_transactions`).

### Security
- JWT encode `userId` + `storeId` + `role` — tránh JOIN `store_members` trên mỗi request.
- Mọi query nghiệp vụ phải filter `store_id = storeId từ JWT` — không để user truy cập data của store khác.
