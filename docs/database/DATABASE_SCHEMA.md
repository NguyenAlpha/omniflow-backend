# Database Schema — OmniFlow

> Tất cả bảng đều có `created_at TIMESTAMPTZ` và `updated_at TIMESTAMPTZ` (trừ các bảng giao dịch chỉ có `created_at`).
> Kiểu tiền tệ và số lượng dùng `NUMERIC(15, 2)` để tránh lỗi làm tròn floating-point.
>
> **Soft delete:** các bảng có `deleted_at` — khi xoá chỉ set timestamp, không xoá vật lý. Mọi query cần thêm `WHERE deleted_at IS NULL`.
> **Không cho xoá:** bảng tài chính (`orders`, `purchase_orders`, `payments`, `inventory_transactions`) — chỉ được huỷ qua `status`.
>
> Các vấn đề đã giải tuyết: RBAC (Role-Based Access Control), multi-tenant
> 
> Tương lai nâng cấp: Feature Flag / Feature Toggle

> **[REDESIGN]** Phân cấp entity:
> ```
> businesses   ← tenant cấp cao nhất, sở hữu subscription
>   └── stores (chi nhánh)
>         └── warehouses (kho hàng)
> ```
> `businesses` → `stores` (1:N) → `warehouses` (1:N)
> Tất cả giao dịch (orders, purchase_orders, payments...) thuộc về `store` (chi nhánh).
> Catalog (products, categories, customers, suppliers) thuộc về `business`.
> Subscription tính theo `business`, không theo chi nhánh.

---

## 1. Người dùng & Phân quyền & Cửa hàng

### `users` — Tài khoản (authentication)
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `username` | VARCHAR(50) | NOT NULL, UNIQUE (partial) | Tên đăng nhập |
| `email` | VARCHAR(100) | NOT NULL, UNIQUE (partial) | Email đăng ký |
| `password_hash` | VARCHAR(255) | NOT NULL | Mật khẩu đã hash (bcrypt) |
| `full_name` | VARCHAR(200) | NOT NULL | Họ và tên |
| `phone` | VARCHAR(20) | | Số điện thoại |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Trạng thái tài khoản — false = tạm khoá |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

> Độc lập hoàn toàn — không biết gì về store hay role. Dễ mở rộng OAuth, 2FA mà không ảnh hưởng nghiệp vụ.
>
> **Unique partial (V7):** `uq_users_username_active` / `uq_users_email_active` — UNIQUE chỉ áp dụng
> `WHERE deleted_at IS NULL`, để username/email của user đã xóa mềm được đăng ký lại bởi người mới.

### `roles` — Định nghĩa vai trò (RBAC)
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `name` | VARCHAR(50) | NOT NULL, UNIQUE | Tên vai trò: `SUPER_ADMIN` / `SUPPORT` / `OWNER` / `MANAGER` / `STAFF` |
| `description` | TEXT | | Mô tả vai trò |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |

> Seeded sẵn khi migration: 5 role mặc định. Không xoá được vì được FK tham chiếu từ `user_roles`.

### `businesses` — Doanh nghiệp (tenant cấp cao nhất)
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `name` | VARCHAR(200) | NOT NULL | Tên doanh nghiệp (VD: Shop ABC) |
| `address` | TEXT | | Địa chỉ chính |
| `phone` | VARCHAR(20) | | Số điện thoại |
| `email` | VARCHAR(100) | | Email liên hệ |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Trạng thái — false = tạm khoá toàn bộ |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm |

> Là đơn vị billing — subscription gắn vào đây, không gắn vào chi nhánh.
> Một user (OWNER) sở hữu 1 business, business có nhiều chi nhánh (stores).

### `stores` — Chi nhánh
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses, NOT NULL | Doanh nghiệp sở hữu chi nhánh này |
| `name` | VARCHAR(200) | NOT NULL | Tên chi nhánh (VD: Chi nhánh Quận 1) |
| `address` | TEXT | | Địa chỉ chi nhánh |
| `phone` | VARCHAR(20) | | Số điện thoại |
| `email` | VARCHAR(100) | | Email liên hệ |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Trạng thái — false = tạm đóng chi nhánh |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm |

### `business_members` — Thành viên của doanh nghiệp (metadata)
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `user_id` | BIGINT | FK → users, NOT NULL | Tài khoản liên kết |
| `business_id` | BIGINT | FK → businesses, NOT NULL | Doanh nghiệp thuộc về |
| `joined_date` | DATE | | Ngày gia nhập doanh nghiệp |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Trạng thái trong doanh nghiệp này |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm |

> **UNIQUE(user_id, business_id) WHERE deleted_at IS NULL** — 1 user chỉ có 1 membership trong 1 business.
> Role (OWNER) được quản lý qua `user_roles`, không lưu ở đây.
> Query "user này có quyền vào những business nào": join `business_members` thay vì scan `user_roles`.

### `store_members` — Thành viên của chi nhánh (metadata)
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `user_id` | BIGINT | FK → users, NOT NULL | Tài khoản liên kết |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng thuộc về |
| `position_title` | VARCHAR(100) | | Chức danh hiển thị (VD: Thu ngân, Thủ kho) |
| `joined_date` | DATE | | Ngày gia nhập cửa hàng |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Trạng thái trong cửa hàng này |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

> **UNIQUE(user_id, store_id) WHERE deleted_at IS NULL** — 1 user chỉ có 1 membership trong 1 store.
> Vai trò (OWNER/MANAGER/STAFF) được quản lý qua `user_roles`, không lưu trong bảng này.
>
> **Flow đăng ký:** Tạo `user` → tạo `store` → tạo `store_members` → tạo `user_roles` với role `OWNER` và `store_id` tương ứng.

### `user_roles` — Phân quyền RBAC
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `user_id` | BIGINT | FK → users, NOT NULL | Tài khoản được gán vai trò |
| `role_id` | BIGINT | FK → roles, NOT NULL | Vai trò được gán |
| `business_id` | BIGINT | FK → businesses | Phạm vi business — dùng cho OWNER |
| `store_id` | BIGINT | FK → stores | Phạm vi chi nhánh — dùng cho MANAGER / STAFF |
| `granted_by` | BIGINT | FK → users | Người gán quyền |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Trạng thái phân quyền |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm |

> **Phạm vi 3 cấp:**
> | business_id | store_id | Ý nghĩa |
> |---|---|---|
> | NULL | NULL | Global (SUPER_ADMIN, SUPPORT) |
> | NOT NULL | NULL | Business scope (OWNER — có quyền trên toàn bộ chi nhánh của business) |
> | NULL | NOT NULL | Branch scope (MANAGER, STAFF — chỉ có quyền trong chi nhánh đó) |
>
> **UNIQUE(user_id, role_id, COALESCE(business_id, 0), COALESCE(store_id, 0)) WHERE deleted_at IS NULL**
>
> **Query lấy quyền của user trong một chi nhánh cụ thể:**
> ```sql
> SELECT r.name FROM user_roles ur
> JOIN roles r ON r.id = ur.role_id
> JOIN stores s ON s.id = :storeId
> WHERE ur.user_id = :userId
>   AND (
>     ur.store_id = :storeId                          -- role cấp chi nhánh
>     OR ur.business_id = s.business_id               -- OWNER của business này
>     OR (ur.business_id IS NULL AND ur.store_id IS NULL)  -- global role
>   )
>   AND ur.deleted_at IS NULL AND ur.is_active = true;
> ```

### `subscriptions` — Gói dịch vụ của doanh nghiệp
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses, NOT NULL, UNIQUE | Doanh nghiệp sở hữu gói |
| `plan` | VARCHAR(20) | NOT NULL | Gói: `FREE` / `BASIC` / `PRO` |
| `status` | VARCHAR(20) | NOT NULL | Trạng thái: `ACTIVE` / `EXPIRED` / `CANCELLED` |
| `billing_cycle` | VARCHAR(20) | | Chu kỳ: `MONTHLY` / `YEARLY` — null nếu FREE |
| `max_stores` | INTEGER | | Giới hạn cửa hàng — null = không giới hạn |
| `max_staff` | INTEGER | | Giới hạn nhân viên — null = không giới hạn |
| `max_products` | INTEGER | | Giới hạn sản phẩm — null = không giới hạn |
| `max_warehouses` | INTEGER | | Giới hạn kho — null = không giới hạn |
| `started_at` | TIMESTAMPTZ | NOT NULL | Thời điểm bắt đầu gói |
| `expires_at` | TIMESTAMPTZ | | Thời điểm hết hạn — null nếu FREE |
| `pending_plan` | VARCHAR(20) | | Gói downgrade đã đặt lịch — áp dụng cuối chu kỳ (V3) |
| `pending_billing_cycle` | VARCHAR(20) | | Dự trữ — hiện luôn null (V3) |
| `expiry_warning_sent_at` | TIMESTAMPTZ | | Đã gửi email cảnh báo hết hạn cho chu kỳ hiện tại — chống spam (V10) |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |

> `max_*` là **bản sao** giới hạn từ `subscription_plans`: sub `ACTIVE` mang giới hạn của
> gói đang dùng, sub không `ACTIVE` mang giới hạn `FREE`. Admin sửa gói → cập nhật ngay.
>
> Cột `max_orders_per_month` đã bị loại bỏ — chưa từng được gán giá trị (dead code).

### `subscription_plans` — Giá và giới hạn của từng gói (admin sửa được)
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `code` | VARCHAR(20) | PK, CHECK IN (`FREE`,`BASIC`,`PRO`) | Gói — danh sách cố định |
| `monthly_price` | NUMERIC(15,2) | NOT NULL, >= 0 | Giá tháng (VND) |
| `yearly_price` | NUMERIC(15,2) | NOT NULL, >= 0 | Giá năm (VND) |
| `max_stores` | INTEGER | >= 0 | Giới hạn cửa hàng — null = không giới hạn |
| `max_staff` | INTEGER | >= 0 | Giới hạn nhân viên — null = không giới hạn |
| `max_products` | INTEGER | >= 0 | Giới hạn sản phẩm — null = không giới hạn |
| `max_warehouses` | INTEGER | >= 0 | Giới hạn kho — null = không giới hạn |
| `version` | BIGINT | NOT NULL | Optimistic lock |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Lần sửa gần nhất |

> CHECK `ck_subscription_plans_free_price`: giá gói `FREE` luôn bằng 0. Seed trong V2:
> FREE 0/0 — 1/0/50/1; BASIC 199.000/1.990.000 — 2/20/200/20; PRO 499.000/4.990.000 — 3/∞/∞/∞.

---

## 2. Danh mục & Đơn vị tính

### `categories` — Danh mục sản phẩm
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses, NOT NULL | Doanh nghiệp sở hữu — dùng chung toàn bộ chi nhánh |
| `name` | VARCHAR(100) | NOT NULL | Tên danh mục (VD: Đồ uống, Thực phẩm) |
| `description` | TEXT | | Mô tả thêm |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_by` | BIGINT | FK → users, NOT NULL | Người tạo |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

> **UNIQUE(business_id, name) WHERE deleted_at IS NULL**

### `units` — Đơn vị tính
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses | Doanh nghiệp sở hữu — **null = system unit** (dùng chung, không sửa/xoá được) |
| `name` | VARCHAR(50) | NOT NULL | Tên đơn vị (VD: Cái, Kg, Thùng, Hộp) |
| `abbreviation` | VARCHAR(10) | NOT NULL | Ký hiệu viết tắt (VD: kg, pcs, box) |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

> **UNIQUE(name, business_id) WHERE deleted_at IS NULL** — cho phép Business A và Business B đều có unit tên "Thùng" riêng; system units (business_id IS NULL) cũng unique theo name.
>
> **Quy tắc phân quyền:**
> - `business_id IS NULL` (system unit): chỉ SUPER_ADMIN được tạo/sửa — seeded sẵn khi deploy (Cái, Kg, Lít, Hộp, Thùng, Gói, ...)
> - `business_id IS NOT NULL` (business unit): OWNER của business đó được tạo/sửa/xoá mềm
>
> **Khi query sản phẩm:** lấy cả system units lẫn units của business hiện tại:
> ```sql
> SELECT * FROM units WHERE (business_id = :businessId OR business_id IS NULL) AND deleted_at IS NULL;
> ```

---

## 3. Sản phẩm

### `products` — Sản phẩm
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses, NOT NULL | Doanh nghiệp sở hữu — catalog dùng chung toàn bộ chi nhánh |
| `sku` | VARCHAR(50) | NOT NULL | Mã sản phẩm |
| `name` | VARCHAR(200) | NOT NULL | Tên sản phẩm |
| `description` | TEXT | | Mô tả chi tiết |
| `category_id` | BIGINT | FK → categories | Danh mục sản phẩm |
| `unit_id` | BIGINT | FK → units, NOT NULL | Đơn vị tính mặc định |
| `cost_price` | NUMERIC(15,2) | NOT NULL | Giá nhập (giá vốn) |
| `selling_price` | NUMERIC(15,2) | NOT NULL | Giá bán lẻ |
| `min_stock_level` | INTEGER | NOT NULL, DEFAULT 0 | Tồn kho tối thiểu — cảnh báo khi dưới mức này |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Trạng thái — false = ngừng kinh doanh |
| `search_vector` | TSVECTOR | | Phục vụ tìm kiếm toàn văn |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

> **UNIQUE(business_id, sku) WHERE deleted_at IS NULL**

### `price_history` — Lịch sử thay đổi giá sản phẩm
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses, NOT NULL | Doanh nghiệp sở hữu — cho phép filter/index trực tiếp |
| `product_id` | BIGINT | FK → products, NOT NULL | Sản phẩm bị thay đổi giá |
| `old_cost_price` | NUMERIC(15,2) | NOT NULL | Giá nhập cũ |
| `new_cost_price` | NUMERIC(15,2) | NOT NULL | Giá nhập mới |
| `old_selling_price` | NUMERIC(15,2) | NOT NULL | Giá bán cũ |
| `new_selling_price` | NUMERIC(15,2) | NOT NULL | Giá bán mới |
| `changed_by` | BIGINT | FK → users, NOT NULL | Người thực hiện thay đổi |
| `changed_at` | TIMESTAMPTZ | NOT NULL | Thời điểm thay đổi |

> Không cho phép xoá — cần để tính đúng margin lợi nhuận cho đơn hàng cũ khi giá thay đổi.

---

## 4. Kho hàng & Tồn kho

### `warehouses` — Kho hàng
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng sở hữu |
| `name` | VARCHAR(100) | NOT NULL | Tên kho (VD: Kho chính, Kho chi nhánh Q1) |
| `address` | TEXT | | Địa chỉ kho |
| `is_active` | BOOLEAN | NOT NULL, DEFAULT true | Trạng thái hoạt động |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

### `inventory` — Tồn kho theo sản phẩm và kho
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `product_id` | BIGINT | FK → products, NOT NULL | Sản phẩm |
| `warehouse_id` | BIGINT | FK → warehouses, NOT NULL | Kho chứa |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng sở hữu |
| `quantity` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Số lượng tồn kho hiện tại |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật gần nhất |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

> **UNIQUE(product_id, warehouse_id) WHERE deleted_at IS NULL** (`ux_inventory_product_warehouse`)

### `inventory_transactions` — Lịch sử giao dịch kho
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng thực hiện |
| `product_id` | BIGINT | FK → products, NOT NULL | Sản phẩm |
| `warehouse_id` | BIGINT | FK → warehouses, NOT NULL | Kho thực hiện |
| `type` | VARCHAR(20) | NOT NULL | Loại giao dịch: `IN` / `OUT` / `TRANSFER` / `ADJUSTMENT` |
| `quantity` | NUMERIC(15,2) | NOT NULL, CHECK <> 0 | Số lượng thay đổi — `IN`/`OUT`: luôn dương (chiều do `type` quyết định); `TRANSFER`/`ADJUSTMENT`: delta có dấu (chân xuất transfer và điều chỉnh giảm là số âm) |
| `order_id` | BIGINT | FK → orders | Đơn hàng nguồn — null nếu từ purchase_order hoặc ADJUSTMENT/TRANSFER |
| `purchase_order_id` | BIGINT | FK → purchase_orders | Đơn nhập nguồn — null nếu từ order hoặc ADJUSTMENT/TRANSFER |
| `note` | TEXT | | Ghi chú thêm |
| `created_by` | BIGINT | FK → users, NOT NULL | Người thực hiện |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm giao dịch |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |

> Không cho phép xoá — dữ liệu lịch sử kho.
> `order_id` và `purchase_order_id` đều nullable — cả 2 null khi giao dịch không gắn với đơn nào (`ADJUSTMENT` kiểm kê tay, `TRANSFER` giữa 2 kho).
> `TRANSFER` ghi 2 bản ghi: chân xuất quantity âm, chân nhập quantity dương.
> **Khi aggregate báo cáo: KHÔNG SUM(quantity) trộn lẫn các type** — quy ước dấu khác nhau.

---

## 5. Khách hàng & Nhà cung cấp

### `customers` — Khách hàng
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses, NOT NULL | Doanh nghiệp sở hữu — khách hàng dùng chung toàn chi nhánh |
| `code` | VARCHAR(20) | NOT NULL | Mã khách hàng (VD: KH-0001) |
| `name` | VARCHAR(200) | NOT NULL | Tên khách hàng |
| `phone` | VARCHAR(20) | | Số điện thoại |
| `email` | VARCHAR(100) | | Email |
| `address` | TEXT | | Địa chỉ |
| `debt_balance` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Tổng tiền khách đang nợ cửa hàng |
| `search_vector` | TSVECTOR | | Phục vụ tìm kiếm toàn văn |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_by` | BIGINT | FK → users, NOT NULL | Người tạo |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

> **UNIQUE(business_id, code) WHERE deleted_at IS NULL**

### `suppliers` — Nhà cung cấp
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses, NOT NULL | Doanh nghiệp sở hữu — nhà cung cấp dùng chung toàn chi nhánh |
| `code` | VARCHAR(20) | NOT NULL | Mã nhà cung cấp (VD: NCC-0001) |
| `name` | VARCHAR(200) | NOT NULL | Tên nhà cung cấp |
| `phone` | VARCHAR(20) | | Số điện thoại |
| `email` | VARCHAR(100) | | Email |
| `address` | TEXT | | Địa chỉ |
| `debt_balance` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Tổng tiền cửa hàng đang nợ nhà cung cấp |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_by` | BIGINT | FK → users, NOT NULL | Người tạo |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

> **UNIQUE(business_id, code) WHERE deleted_at IS NULL**

---

## 6. Đơn hàng bán

### `orders` — Đơn hàng bán ra
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng thực hiện |
| `order_code` | VARCHAR(20) | NOT NULL | Mã đơn hàng (VD: DH-20240506-001) |
| `customer_id` | BIGINT | FK → customers | Khách hàng (null = khách lẻ) |
| `warehouse_id` | BIGINT | FK → warehouses, NOT NULL | Kho xuất hàng |
| `status` | VARCHAR(20) | NOT NULL | Trạng thái: `PENDING` / `COMPLETED` / `CANCELLED` |
| `subtotal` | NUMERIC(15,2) | NOT NULL | Tổng tiền hàng trước chiết khấu/thuế |
| `discount` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Chiết khấu |
| `discount_type` | VARCHAR(10) | NOT NULL, DEFAULT 'FIXED' | Kiểu giảm giá: `FIXED` / `PERCENT` |
| `tax` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Thuế VAT |
| `total_amount` | NUMERIC(15,2) | NOT NULL | Tổng tiền phải thu (subtotal - discount + tax) |
| `paid_amount` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Số tiền đã thanh toán |
| `debt_amount` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Số tiền còn nợ (total_amount - paid_amount) |
| `note` | TEXT | | Ghi chú đơn hàng |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_by` | BIGINT | FK → users, NOT NULL | Nhân viên tạo đơn |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |

> **UNIQUE(store_id, order_code)** — Không cho phép xoá, chỉ huỷ qua `status = CANCELLED`.

### `order_items` — Chi tiết đơn hàng
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `order_id` | BIGINT | FK → orders, NOT NULL | Đơn hàng chứa dòng này |
| `product_id` | BIGINT | FK → products, NOT NULL | Sản phẩm |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng sở hữu |
| `quantity` | NUMERIC(15,2) | NOT NULL | Số lượng bán |
| `unit_price` | NUMERIC(15,2) | NOT NULL | Đơn giá tại thời điểm bán (snapshot) |
| `discount` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Chiết khấu theo dòng |
| `discount_type` | VARCHAR(10) | NOT NULL, DEFAULT 'FIXED' | Kiểu giảm giá: `FIXED` / `PERCENT` |
| `total_price` | NUMERIC(15,2) | NOT NULL | Thành tiền = quantity × unit_price - discount |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

---

## 7. Hoàn trả hàng

### `return_orders` — Đơn hoàn trả hàng
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng thực hiện |
| `return_code` | VARCHAR(20) | NOT NULL | Mã đơn hoàn trả (VD: HT-20240506-001) |
| `original_order_id` | BIGINT | FK → orders, NOT NULL | Đơn hàng gốc bị hoàn trả |
| `warehouse_id` | BIGINT | FK → warehouses, NOT NULL | Kho nhận hàng trả về |
| `status` | VARCHAR(20) | NOT NULL | Trạng thái: `PENDING` / `COMPLETED` / `CANCELLED` |
| `reason` | TEXT | NOT NULL | Lý do hoàn trả |
| `total_refund` | NUMERIC(15,2) | NOT NULL | Tổng tiền hoàn lại cho khách |
| `refund_method` | VARCHAR(20) | NOT NULL | Hình thức hoàn: `CASH` / `BANK_TRANSFER` / `STORE_CREDIT` |
| `note` | TEXT | | Ghi chú thêm |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_by` | BIGINT | FK → users, NOT NULL | Người tạo đơn hoàn trả |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |

> **UNIQUE(store_id, return_code)**
> Không cho phép xoá — chỉ huỷ qua `status = CANCELLED`.
> Khi `status = COMPLETED`: tồn kho tăng lại, `customers.debt_balance` điều chỉnh nếu cần.

### `return_order_items` — Chi tiết đơn hoàn trả
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng sở hữu — nhất quán với `order_items`, `purchase_order_items` |
| `return_order_id` | BIGINT | FK → return_orders, NOT NULL | Đơn hoàn trả chứa dòng này |
| `product_id` | BIGINT | FK → products, NOT NULL | Sản phẩm hoàn trả |
| `quantity` | NUMERIC(15,2) | NOT NULL | Số lượng hoàn trả |
| `unit_price` | NUMERIC(15,2) | NOT NULL | Đơn giá hoàn (snapshot từ đơn gốc) |
| `total_refund` | NUMERIC(15,2) | NOT NULL | Thành tiền hoàn = quantity × unit_price |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

---

## 8. Đơn nhập hàng

### `purchase_orders` — Đơn nhập hàng từ nhà cung cấp
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng thực hiện |
| `order_code` | VARCHAR(20) | NOT NULL | Mã đơn nhập (VD: PN-20240506-001) |
| `supplier_id` | BIGINT | FK → suppliers, NOT NULL | Nhà cung cấp |
| `warehouse_id` | BIGINT | FK → warehouses, NOT NULL | Kho nhập hàng vào |
| `status` | VARCHAR(20) | NOT NULL | Trạng thái: `PENDING` / `RECEIVED` / `CANCELLED` |
| `total_amount` | NUMERIC(15,2) | NOT NULL | Tổng tiền hàng phải trả |
| `paid_amount` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Số tiền đã trả |
| `debt_amount` | NUMERIC(15,2) | NOT NULL, DEFAULT 0 | Số tiền còn nợ nhà cung cấp |
| `note` | TEXT | | Ghi chú |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_by` | BIGINT | FK → users, NOT NULL | Người tạo đơn |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |

> **UNIQUE(store_id, order_code)** — Không cho phép xoá, chỉ huỷ qua `status = CANCELLED`.

### `purchase_order_items` — Chi tiết đơn nhập
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `purchase_order_id` | BIGINT | FK → purchase_orders, NOT NULL | Đơn nhập chứa dòng này |
| `product_id` | BIGINT | FK → products, NOT NULL | Sản phẩm |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng sở hữu |
| `quantity` | NUMERIC(15,2) | NOT NULL | Số lượng nhập |
| `unit_price` | NUMERIC(15,2) | NOT NULL | Đơn giá nhập |
| `total_price` | NUMERIC(15,2) | NOT NULL | Thành tiền = quantity × unit_price |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `deleted_at` | TIMESTAMPTZ | | Thời điểm xoá mềm — null = chưa xoá |

---

## 8. Thanh toán công nợ

### `payments` — Ghi nhận thanh toán / thu tiền công nợ
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `store_id` | BIGINT | FK → stores, NOT NULL | Cửa hàng thực hiện |
| `customer_id` | BIGINT | FK → customers | Khách hàng thanh toán — null nếu là thanh toán NCC |
| `supplier_id` | BIGINT | FK → suppliers | Nhà cung cấp thanh toán — null nếu là thu tiền KH |
| `amount` | NUMERIC(15,2) | NOT NULL, CHECK > 0 | Số tiền thanh toán |
| `payment_method` | VARCHAR(20) | NOT NULL | Hình thức: `CASH` / `BANK_TRANSFER` / `CREDIT_CARD` / `DEBIT_CARD` / `MOBILE_PAYMENT` / `OTHER` |
| `note` | TEXT | | Ghi chú |
| `public_id` | UUID | NOT NULL, UNIQUE | Khóa sync ổn định |
| `sync_version` | BIGINT | NOT NULL, DEFAULT 0 | Version sync |
| `last_modified_at` | TIMESTAMPTZ | NOT NULL | Lần sửa cuối |
| `last_modified_by_user` | BIGINT | FK → users | Người sửa cuối |
| `last_modified_by_device` | UUID | | Thiết bị sửa cuối |
| `created_by` | BIGINT | FK → users, NOT NULL | Người ghi nhận |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm thanh toán |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |

> Không cho phép xoá — dữ liệu tài chính.
> **CHECK:** `(customer_id IS NOT NULL) != (supplier_id IS NOT NULL)` — bắt buộc đúng 1 trong 2 phải có giá trị.

---

## 9. Audit & Subscription

### `audit_logs` — Nhật ký thao tác hệ thống (V6 tạo lại bảng)
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `user_id` | BIGINT | FK → users | Người thực hiện |
| `business_id` | BIGINT | FK → businesses | Doanh nghiệp liên quan — null nếu là hành động system-level |
| `store_id` | BIGINT | FK → stores | Chi nhánh liên quan — null nếu hành động ở cấp business hoặc system |
| `action` | VARCHAR(50) | NOT NULL | Hành động free-form theo `@Auditable` (VD: `CREATE_ORDER`, `ADJUST_INVENTORY`, `RECEIVE_PURCHASE_ORDER`) — **không có CHECK** |
| `entity_type` | VARCHAR(50) | NOT NULL | Loại entity bị tác động (VD: `Order`, `Product`) |
| `entity_id` | BIGINT | | ID của bản ghi bị tác động |
| `old_value` | JSONB | | Dữ liệu trước khi thay đổi |
| `new_value` | JSONB | | Dữ liệu sau khi thay đổi |
| `ip` | VARCHAR(45) | | IP của người thực hiện |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm thực hiện |

> Không cho phép xoá hay sửa — đây là bằng chứng audit. Bảng append-only, không có `updated_at`.
> Chỉ ghi log cho các hành động nhạy cảm: sửa giá, huỷ đơn, xoá khách hàng, thay đổi role, thay đổi subscription.

### `subscription_invoices` — Lịch sử thanh toán subscription
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khóa chính |
| `business_id` | BIGINT | FK → businesses, NOT NULL | Doanh nghiệp thanh toán |
| `plan` | VARCHAR(20) | NOT NULL | Gói tại thời điểm thanh toán: `FREE` / `BASIC` / `PRO` |
| `billing_cycle` | VARCHAR(20) | NOT NULL | Chu kỳ: `MONTHLY` / `YEARLY` |
| `amount` | NUMERIC(15,2) | NOT NULL | Số tiền thanh toán |
| `status` | VARCHAR(20) | NOT NULL | Trạng thái: `PAID` / `PENDING` / `FAILED` |
| `payment_method` | VARCHAR(20) | | Hình thức: `BANK_TRANSFER` / `CARD` / `MOMO` / ... |
| `period_start` | TIMESTAMPTZ | NOT NULL | Bắt đầu kỳ thanh toán |
| `period_end` | TIMESTAMPTZ | NOT NULL | Kết thúc kỳ thanh toán |
| `paid_at` | TIMESTAMPTZ | | Thời điểm thanh toán thành công — null nếu chưa trả |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo invoice |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật |

> Không cho phép xoá — lịch sử billing.

### `api_traffic_minutely`, `api_traffic_hourly` — Lưu lượng API (V11)
| Tên cột | Kiểu | Ý nghĩa |
|---|---|---|
| `bucket_start` | TIMESTAMPTZ | Đầu phút (minutely) / đầu giờ (hourly) — PK cùng 3 cột dưới |
| `method` | VARCHAR(10) | HTTP method |
| `route` | VARCHAR(200) | Mẫu route Spring, hoặc `(unmatched)` khi request không tới controller |
| `status` | SMALLINT | HTTP status |
| `request_count`, `total_duration_ms`, `max_duration_ms` | BIGINT | Số request, tổng và lớn nhất thời gian xử lý |
| `le_50` … `le_5000`, `gt_5000` | BIGINT | Số request theo khoảng thời gian xử lý (không chồng nhau) để ước lượng phân vị |

> Ghi bằng UPSERT cộng dồn mỗi phút. Minutely giữ 2 ngày, hourly giữ 30 ngày (xóa bởi
> `ApiTrafficRecorder.deleteExpired`, chạy mỗi giờ).

### `api_traffic_business_hourly` — Lưu lượng API theo business (V11)
| Tên cột | Kiểu | Ý nghĩa |
|---|---|---|
| `bucket_start` | TIMESTAMPTZ | Đầu giờ — PK cùng `business_id` |
| `business_id` | BIGINT | Business (không FK — giữ số liệu kể cả khi business bị xóa) |
| `request_count`, `error_count`, `total_duration_ms` | BIGINT | Số request, số 5xx, tổng thời gian xử lý |

> Giữ 30 ngày. Không tính request `/api/admin/**`.

### `sync_change_log` — Nhật ký sync delta
| Tên cột | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `id` | BIGSERIAL | PK | Khoá chính |
| `store_id` | BIGINT | FK → stores, NOT NULL | Tenant sở hữu |
| `table_name` | VARCHAR(50) | NOT NULL | Bảng bị thay đổi |
| `record_public_id` | UUID | NOT NULL | Khóa sync của bản ghi |
| `operation` | VARCHAR(10) | NOT NULL | `INSERT` / `UPDATE` / `DELETE` |
| `sync_version` | BIGINT | NOT NULL | Version tăng dần theo store |
| `changed_at` | TIMESTAMPTZ | NOT NULL | Thời điểm thay đổi |
| `changed_by_device` | UUID | | Thiết bị gây thay đổi |

> **CHECK:** `chk_sync_log_operation` — operation IN (`INSERT`, `UPDATE`, `DELETE`).
> Index: `idx_sync_log_store_version (store_id, sync_version)` — pull delta theo store.

---

## 10. Indexes

> Ngoài các index tự động từ PK và UNIQUE, cần tạo thêm các index sau.

### FK columns — tránh full scan khi JOIN
```sql
CREATE INDEX idx_admin_profiles_user_id       ON admin_profiles (user_id);
CREATE INDEX idx_stores_business_id           ON stores (business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_business_members_user_id     ON business_members (user_id);
CREATE INDEX idx_business_members_business_id ON business_members (business_id);
CREATE INDEX idx_store_members_user_id        ON store_members (user_id);
CREATE INDEX idx_store_members_store_id       ON store_members (store_id);
CREATE INDEX idx_categories_business_id       ON categories (business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_business_id         ON products (business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_category_id         ON products (category_id);
CREATE INDEX idx_warehouses_store_id          ON warehouses (store_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_inventory_warehouse_id       ON inventory (warehouse_id);
CREATE INDEX idx_customers_business_id        ON customers (business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_suppliers_business_id        ON suppliers (business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_orders_customer_id           ON orders (customer_id);
CREATE INDEX idx_orders_warehouse_id          ON orders (warehouse_id);
CREATE INDEX idx_order_items_order_id         ON order_items (order_id);
CREATE INDEX idx_order_items_product_id       ON order_items (product_id);
CREATE INDEX idx_purchase_orders_supplier_id  ON purchase_orders (supplier_id);
CREATE INDEX idx_purchase_order_items_po_id   ON purchase_order_items (purchase_order_id);
CREATE INDEX idx_payments_customer_id         ON payments (customer_id);
CREATE INDEX idx_payments_supplier_id         ON payments (supplier_id);
CREATE INDEX idx_inventory_tx_product_id      ON inventory_transactions (product_id);
```

### Composite indexes — cho query nghiệp vụ phổ biến
```sql
-- Lọc đơn hàng theo chi nhánh + trạng thái (dashboard chi nhánh)
CREATE INDEX idx_orders_store_status          ON orders (store_id, status);

-- Lọc đơn hàng theo chi nhánh + thời gian (báo cáo doanh thu chi nhánh)
CREATE INDEX idx_orders_store_created         ON orders (store_id, created_at DESC);

-- Lịch sử kho theo chi nhánh + thời gian
CREATE INDEX idx_inv_tx_store_created         ON inventory_transactions (store_id, created_at DESC);

-- Lọc đơn nhập theo chi nhánh + trạng thái
CREATE INDEX idx_po_store_status              ON purchase_orders (store_id, status);

-- Tồn kho: unique partial theo soft delete (thay cho UNIQUE thường)
CREATE UNIQUE INDEX ux_inventory_product_warehouse ON inventory (product_id, warehouse_id) WHERE deleted_at IS NULL;

-- inventory_transactions: FK mới sau khi bỏ polymorphic
CREATE INDEX idx_inv_tx_order_id              ON inventory_transactions (order_id);
CREATE INDEX idx_inv_tx_po_id                 ON inventory_transactions (purchase_order_id);

-- Báo cáo tồn kho dưới mức tối thiểu
CREATE INDEX idx_inventory_product_qty        ON inventory (product_id, quantity);

-- Công nợ khách hàng — filter nhanh khách có nợ
CREATE INDEX idx_customers_business_debt
  ON customers (business_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0;

-- Công nợ nhà cung cấp
CREATE INDEX idx_suppliers_business_debt
  ON suppliers (business_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0;

-- is_active + deleted_at composite
CREATE INDEX idx_products_business_active
  ON products (business_id, is_active) WHERE deleted_at IS NULL;
CREATE INDEX idx_warehouses_store_active
  ON warehouses (store_id, is_active) WHERE deleted_at IS NULL;

-- return_orders
CREATE INDEX idx_return_orders_store_id        ON return_orders (store_id);
CREATE INDEX idx_return_orders_original_order  ON return_orders (original_order_id);
CREATE INDEX idx_return_order_items_ro_id      ON return_order_items (return_order_id);
CREATE INDEX idx_return_order_items_store_id   ON return_order_items (store_id);

-- price_history
CREATE INDEX idx_price_history_product_id        ON price_history (product_id, changed_at DESC);
CREATE INDEX idx_price_history_business_created  ON price_history (business_id, changed_at DESC);

-- units per-business
CREATE INDEX idx_units_business_id               ON units (business_id) WHERE business_id IS NOT NULL AND deleted_at IS NULL;

-- audit_logs (V6) — query theo entity hoặc theo người thực hiện
CREATE INDEX idx_audit_logs_business_id        ON audit_logs (business_id, created_at DESC);
CREATE INDEX idx_audit_logs_store_id           ON audit_logs (store_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity             ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_user_id            ON audit_logs (user_id, created_at DESC);
CREATE INDEX idx_audit_logs_created_at         ON audit_logs (created_at DESC);

-- subscription_invoices
CREATE INDEX idx_sub_invoices_business_id      ON subscription_invoices (business_id, created_at DESC);
CREATE INDEX idx_sub_invoices_status           ON subscription_invoices (business_id, status) WHERE status = 'PENDING';

-- sync & FK bổ sung (đã có trong V1)
CREATE INDEX idx_sync_log_store_version         ON sync_change_log (store_id, sync_version);
CREATE INDEX idx_payments_store_created         ON payments (store_id, created_at DESC);
CREATE INDEX idx_order_items_store_id           ON order_items (store_id);
CREATE INDEX idx_inventory_tx_warehouse_id      ON inventory_transactions (warehouse_id);
CREATE INDEX idx_purchase_orders_warehouse_id   ON purchase_orders (warehouse_id);
CREATE INDEX idx_return_order_items_product_id  ON return_order_items (product_id);
CREATE INDEX idx_inventory_store_id             ON inventory (store_id);

-- full-text search
CREATE INDEX idx_products_search_vector  ON products  USING GIN (search_vector);
CREATE INDEX idx_customers_search_vector ON customers USING GIN (search_vector);
```

---

## 10. CHECK Constraints

```sql
-- Giá trị tiền không âm
ALTER TABLE products         ADD CONSTRAINT chk_products_price
  CHECK (cost_price >= 0 AND selling_price >= 0);
ALTER TABLE orders           ADD CONSTRAINT chk_orders_amounts
  CHECK (subtotal >= 0 AND discount >= 0 AND tax >= 0 AND total_amount >= 0 AND paid_amount >= 0 AND debt_amount >= 0);
ALTER TABLE purchase_orders  ADD CONSTRAINT chk_po_amounts
  CHECK (total_amount >= 0 AND paid_amount >= 0 AND debt_amount >= 0);
ALTER TABLE payments         ADD CONSTRAINT chk_payments_amount
  CHECK (amount > 0);
ALTER TABLE inventory        ADD CONSTRAINT chk_inventory_qty
  CHECK (quantity >= 0);

-- Enum-like fields
ALTER TABLE orders           ADD CONSTRAINT chk_orders_status
  CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'));
ALTER TABLE purchase_orders  ADD CONSTRAINT chk_po_status
  CHECK (status IN ('PENDING', 'RECEIVED', 'CANCELLED'));
ALTER TABLE payments         ADD CONSTRAINT chk_payments_method
  CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'CREDIT_CARD', 'DEBIT_CARD', 'MOBILE_PAYMENT', 'OTHER'));
ALTER TABLE subscriptions    ADD CONSTRAINT chk_subscriptions_plan
  CHECK (plan IN ('FREE', 'BASIC', 'PRO'));
ALTER TABLE subscriptions    ADD CONSTRAINT chk_subscriptions_status
  CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED'));

-- payments: đúng 1 trong 2 phải có giá trị
ALTER TABLE payments         ADD CONSTRAINT chk_payments_reference
  CHECK ((customer_id IS NOT NULL) != (supplier_id IS NOT NULL));

-- return_orders
ALTER TABLE return_orders    ADD CONSTRAINT chk_return_orders_status
  CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'));
ALTER TABLE return_orders    ADD CONSTRAINT chk_return_orders_refund
  CHECK (total_refund >= 0);
ALTER TABLE return_orders    ADD CONSTRAINT chk_return_orders_method
  CHECK (refund_method IN ('CASH', 'BANK_TRANSFER', 'STORE_CREDIT'));

-- inventory_transactions
ALTER TABLE inventory_transactions ADD CONSTRAINT chk_inv_tx_type
  CHECK (type IN ('IN', 'OUT', 'TRANSFER', 'ADJUSTMENT'));
-- quantity <> 0 (không phải > 0): TRANSFER/ADJUSTMENT ghi delta có dấu
ALTER TABLE inventory_transactions ADD CONSTRAINT chk_inv_tx_qty
  CHECK (quantity <> 0);

-- sync_change_log
ALTER TABLE sync_change_log  ADD CONSTRAINT chk_sync_log_operation
  CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE'));

-- audit_logs: KHÔNG có CHECK trên action — action là free-form theo @Auditable
-- (bảng được V6 tạo lại, CHECK cũ CREATE/UPDATE/DELETE đã bỏ)

-- subscription_invoices
ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_status
  CHECK (status IN ('PAID', 'PENDING', 'FAILED'));
ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_amount
  CHECK (amount >= 0);
ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_period
  CHECK (period_end > period_start);

ALTER TABLE orders       ADD CONSTRAINT chk_orders_discount_type
  CHECK (discount_type IN ('FIXED', 'PERCENT'));
ALTER TABLE order_items  ADD CONSTRAINT chk_order_items_discount_type
  CHECK (discount_type IN ('FIXED', 'PERCENT'));
```

---

## 11. Materialized Views (PostgreSQL)

> Dùng cho các báo cáo tổng hợp — tránh full scan mỗi lần chủ quán xem dashboard.
> Refresh định kỳ hoặc sau mỗi sự kiện quan trọng (order completed, payment recorded).

### `mv_monthly_revenue` — Doanh thu theo tháng (theo chi nhánh)
```sql
CREATE MATERIALIZED VIEW mv_monthly_revenue AS
SELECT s.business_id,
       o.store_id,
       DATE_TRUNC('month', o.created_at)::DATE AS month,
       SUM(o.total_amount)                     AS revenue,
       SUM(o.paid_amount)                      AS collected,
       SUM(o.debt_amount)                      AS uncollected,
       COUNT(*)                                AS order_count
FROM orders o
JOIN stores s ON s.id = o.store_id
WHERE o.status = 'COMPLETED'
GROUP BY s.business_id, o.store_id, DATE_TRUNC('month', o.created_at);

CREATE UNIQUE INDEX ON mv_monthly_revenue (store_id, month);
CREATE INDEX ON mv_monthly_revenue (business_id, month); -- aggregate toàn business
```

### `mv_inventory_summary` — Tổng tồn kho theo sản phẩm
```sql
CREATE MATERIALIZED VIEW mv_inventory_summary AS
SELECT p.business_id,
       p.id          AS product_id,
       p.name        AS product_name,
       p.sku,
       p.min_stock_level,
       SUM(i.quantity) AS total_quantity,
       CASE WHEN SUM(i.quantity) < p.min_stock_level THEN true ELSE false END AS is_low_stock
FROM products p
JOIN inventory i ON i.product_id = p.id
WHERE p.deleted_at IS NULL
GROUP BY p.business_id, p.id, p.name, p.sku, p.min_stock_level;

CREATE UNIQUE INDEX ON mv_inventory_summary (business_id, product_id);
CREATE INDEX ON mv_inventory_summary (business_id, is_low_stock) WHERE is_low_stock = true;
```

### Refresh strategy
```sql
-- Refresh sau mỗi order completed (gọi trong @TransactionalEventListener)
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_revenue;

-- Refresh sau mỗi inventory_transaction (hoặc batch mỗi 5 phút)
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_inventory_summary;
```

---

## 12. `debt_balance` Invariant Rules

> `customers.debt_balance` và `suppliers.debt_balance` là giá trị denormalized — tính nhanh hơn aggregate mỗi lần hiển thị, nhưng **bắt buộc phải cập nhật trong cùng 1 transaction** với sự kiện gây ra thay đổi. Không có trigger hay constraint DB nào enforce điều này — trách nhiệm hoàn toàn thuộc tầng Service.

### Quy tắc cập nhật (bắt buộc enforce trong Service layer)

| Sự kiện | Hành động |
|---|---|
| `orders.status` → `COMPLETED` và `debt_amount > 0` | `customers.debt_balance += order.debt_amount` |
| `orders.status` → `CANCELLED` (đã từng `COMPLETED`) | `customers.debt_balance -= order.debt_amount` |
| Ghi nhận `payments` cho customer | `customers.debt_balance -= payment.amount` |
| `return_orders.status` → `COMPLETED` và hoàn tiền vào nợ | `customers.debt_balance -= return_order.total_refund` |
| `purchase_orders.status` → `RECEIVED` và `debt_amount > 0` | `suppliers.debt_balance += purchase_order.debt_amount` |
| `purchase_orders.status` → `CANCELLED` (đã từng `RECEIVED`) | `suppliers.debt_balance -= purchase_order.debt_amount` |
| Ghi nhận `payments` cho supplier | `suppliers.debt_balance -= payment.amount` |

### Invariant verification query (chạy trong integration test và cron hàng ngày)

```sql
-- Kiểm tra drift của customers.debt_balance
-- customers giờ là business-level; orders vẫn ở store (branch) level — join bình thường
-- Kết quả phải rỗng — bất kỳ row nào trả về là có bug
SELECT c.id,
       c.name,
       c.debt_balance                                                             AS stored,
       COALESCE(SUM(o.debt_amount) FILTER (WHERE o.status = 'COMPLETED'), 0)
         - COALESCE(SUM(p.amount), 0)                                             AS computed,
       c.debt_balance
         - COALESCE(SUM(o.debt_amount) FILTER (WHERE o.status = 'COMPLETED'), 0)
         + COALESCE(SUM(p.amount), 0)                                             AS drift
FROM customers c
LEFT JOIN orders   o ON o.customer_id = c.id
LEFT JOIN payments p ON p.customer_id = c.id
GROUP BY c.id, c.name, c.debt_balance
HAVING ABS(
  c.debt_balance
  - COALESCE(SUM(o.debt_amount) FILTER (WHERE o.status = 'COMPLETED'), 0)
  + COALESCE(SUM(p.amount), 0)
) > 0.01;

-- Tương tự cho suppliers
SELECT s.id,
       s.name,
       s.debt_balance                                                                  AS stored,
       COALESCE(SUM(po.debt_amount) FILTER (WHERE po.status = 'RECEIVED'), 0)
         - COALESCE(SUM(p.amount), 0)                                                  AS computed,
       s.debt_balance
         - COALESCE(SUM(po.debt_amount) FILTER (WHERE po.status = 'RECEIVED'), 0)
         + COALESCE(SUM(p.amount), 0)                                                  AS drift
FROM suppliers s
LEFT JOIN purchase_orders po ON po.supplier_id = s.id
LEFT JOIN payments        p  ON p.supplier_id  = s.id
GROUP BY s.id, s.name, s.debt_balance
HAVING ABS(
  s.debt_balance
  - COALESCE(SUM(po.debt_amount) FILTER (WHERE po.status = 'RECEIVED'), 0)
  + COALESCE(SUM(p.amount), 0)
) > 0.01;
```

### Checklist khi viết Service

- [ ] `OrderService.completeOrder()` — cập nhật `customers.debt_balance` trong cùng `@Transactional`
- [ ] `OrderService.cancelOrder()` — rollback `customers.debt_balance` nếu order đã `COMPLETED`
- [ ] `PaymentService.recordPayment()` — cập nhật `customers.debt_balance` hoặc `suppliers.debt_balance`
- [ ] `ReturnOrderService.completeReturn()` — điều chỉnh `customers.debt_balance` nếu hoàn vào nợ
- [ ] `PurchaseOrderService.receiveOrder()` — cập nhật `suppliers.debt_balance`
- [ ] `PurchaseOrderService.cancelOrder()` — rollback `suppliers.debt_balance` nếu đã `RECEIVED`
- [ ] Integration test: chạy invariant verification query sau mỗi scenario, assert kết quả rỗng

---
### 13. DDL mẫu quan trọng (Local-first)
```sql
-- 1) Thêm cột sync chuẩn (ví dụ: products)
ALTER TABLE products
  ADD COLUMN public_id UUID NOT NULL DEFAULT gen_random_uuid(),
  ADD COLUMN sync_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ADD COLUMN last_modified_by_user BIGINT,
  ADD COLUMN last_modified_by_device UUID;

CREATE UNIQUE INDEX ux_products_public_id ON products (public_id);
CREATE INDEX idx_products_sync_version ON products (sync_version);
```
