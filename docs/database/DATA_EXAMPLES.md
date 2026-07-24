# Data Examples — Cấu trúc dữ liệu theo Role

Minh họa data thực tế trên DB cho từng loại role. Đọc trước khi thiết kế hoặc debug API liên quan đến phân quyền.

---

## Scenario

```
Business "Coffee Chain" (id=1)
  ├── Store "Chi nhánh Q1" (id=1)
  └── Store "Chi nhánh Q3" (id=2)

Business "Bakery XYZ" (id=2)
  └── Store "Cửa hàng chính" (id=3)
```

| Người | Role |
|:------|:-----|
| `admin` | SUPER_ADMIN — quản trị hệ thống |
| `nguyen.an` | OWNER của Coffee Chain |
| `vo.em` | BUSINESS_MANAGER (trợ lý) của Coffee Chain — quản mọi store, không đụng billing |
| `tran.bich` | MANAGER ở Q1, STAFF ở Q3 (cùng 1 business) |
| `le.cuong` | STAFF ở Chi nhánh Q1 |
| `pham.dao` | OWNER của Bakery XYZ (business khác) |

---

## Bảng `users`

| id | username | full_name | is_active |
|:--:|:---------|:----------|:---------:|
| 1 | admin | Admin Hệ Thống | true |
| 2 | nguyen.an | Nguyễn Văn An | true |
| 3 | tran.bich | Trần Thị Bích | true |
| 4 | le.cuong | Lê Văn Cường | true |
| 5 | pham.dao | Phạm Thị Đào | true |
| 6 | vo.em | Võ Thị Em | true |

---

## Bảng `businesses`

| id | name | is_active |
|:--:|:-----|:---------:|
| 1 | Coffee Chain | true |
| 2 | Bakery XYZ | true |

---

## Bảng `stores`

| id | business_id | name | is_active |
|:--:|:-----------:|:-----|:---------:|
| 1 | 1 | Chi nhánh Q1 | true |
| 2 | 1 | Chi nhánh Q3 | true |
| 3 | 2 | Cửa hàng chính | true |

---

## Bảng `user_roles` — bảng kiểm soát quyền truy cập

| id | user_id | role_id | business_id | store_id | Ý nghĩa |
|:--:|:-------:|:-------:|:-----------:|:--------:|:--------|
| 1 | 1 | 1 `SUPER_ADMIN` | **NULL** | **NULL** | Global — không gắn đâu cả |
| 2 | 2 | 3 `OWNER` | **1** | **NULL** | OWNER của Coffee Chain |
| 3 | 3 | 4 `MANAGER` | **NULL** | **1** | MANAGER của Chi nhánh Q1 |
| 4 | 3 | 5 `STAFF` | **NULL** | **2** | STAFF của Chi nhánh Q3 |
| 5 | 4 | 5 `STAFF` | **NULL** | **1** | STAFF của Chi nhánh Q1 |
| 6 | 5 | 3 `OWNER` | **2** | **NULL** | OWNER của Bakery XYZ |
| 7 | 6 | 6 `BUSINESS_MANAGER` | **1** | **NULL** | Trợ lý của Coffee Chain |

**Quy tắc NULL/SET:**

```
business_id = NULL, store_id = NULL  →  Global role  (SUPER_ADMIN, SUPPORT)
business_id = SET,  store_id = NULL  →  Business-level (OWNER hoặc BUSINESS_MANAGER)
business_id = NULL, store_id = SET   →  Store MANAGER / STAFF
business_id = SET,  store_id = SET   →  ❌ Không hợp lệ (CHECK constraint)
```

> OWNER vs BUSINESS_MANAGER cùng ở dạng `business SET, store NULL` — phân biệt bằng `role_id`.
> Cả hai đều với tới mọi store; nhưng chỉ OWNER được các thao tác owner-only (billing/subscription,
> hồ sơ business, tạo store, quản lý trợ lý).

---

## Bảng `business_members` — roster thành viên cấp business (OWNER + trợ lý)

| id | user_id | business_id | joined_date | is_active |
|:--:|:-------:|:-----------:|:-----------:|:---------:|
| 1 | 2 | 1 | 2025-01-10 | true |
| 2 | 5 | 2 | 2025-03-01 | true |
| 3 | 6 | 1 | 2025-05-01 | true |

> `nguyen.an` (OWNER) và `vo.em` (trợ lý) đều có record — roster chứa mọi thành viên cấp business.
> `tran.bich`, `le.cuong` không có record — họ là store member.
> `admin` không có record — SUPER_ADMIN không gắn với business.

---

## Bảng `store_members` — thông tin nhân viên store

| id | user_id | store_id | position_title | joined_date | is_active |
|:--:|:-------:|:--------:|:--------------|:-----------:|:---------:|
| 1 | 3 | 1 | Trưởng ca | 2025-02-01 | true |
| 2 | 3 | 2 | NULL | 2025-04-15 | true |
| 3 | 4 | 1 | Thu ngân | 2025-02-15 | true |

> `nguyen.an` (OWNER) không có record — OWNER không cần store membership,
> quyền truy cập mọi store được suy ra động từ `user_roles.business_id`.

---

## Tóm tắt: ai có record ở đâu

| User | `user_roles` | `business_members` | `store_members` |
|:-----|:------------:|:------------------:|:---------------:|
| admin (SUPER_ADMIN) | ✅ `biz=NULL, store=NULL` | ❌ | ❌ |
| nguyen.an (OWNER) | ✅ `biz=1, store=NULL` | ✅ | ❌ |
| vo.em (BUSINESS_MANAGER) | ✅ `biz=1, store=NULL` | ✅ | ❌ |
| tran.bich (MANAGER Q1 + STAFF Q3) | ✅ 2 rows | ❌ | ✅ 2 rows |
| le.cuong (STAFF Q1) | ✅ 1 row | ❌ | ✅ 1 row |
| pham.dao (OWNER biz khác) | ✅ `biz=2, store=NULL` | ✅ | ❌ |

---

## Tại sao cần cả `user_roles` lẫn `store_members` / `business_members`?

| Bảng | Mục đích |
|:-----|:---------|
| `user_roles` | Kiểm tra quyền truy cập API (`@PreAuthorize`) |
| `store_members` | Metadata nhân viên — `position_title`, `joined_date`, hiển thị danh sách nhân viên |
| `business_members` | Roster cấp business (OWNER + trợ lý) — lịch sử gia nhập, quản lý thành viên business |

`user_roles` là **access control**. `store_members` / `business_members` là **organizational data**.
Tách biệt để thay đổi role không ảnh hưởng thông tin nhân sự và ngược lại.

---

## Redis cache tương ứng sau khi login

Sau lần đầu user gọi API có `@PreAuthorize`, các key sau được sinh ra:

| Redis key | Giá trị | Sinh từ |
|:----------|:--------|:--------|
| `business:role:2:1` | `"ROLE_OWNER"` | nguyen.an check quyền trên Coffee Chain |
| `business:role:6:1` | `"ROLE_BUSINESS_MANAGER"` | vo.em (trợ lý) check quyền trên Coffee Chain |
| `store:business:1` | `"1"` | resolve businessId của Chi nhánh Q1 |
| `store:role:3:1` | `"ROLE_MANAGER"` | tran.bich check quyền tại Q1 |
| `store:role:3:2` | `"ROLE_STAFF"` | tran.bich check quyền tại Q3 |
| `business:member:3:1` | `"ROLE_MANAGER"` | tran.bich check catalog của Coffee Chain |

Xem chi tiết tại [REDIS_CACHE.md](../REDIS_CACHE.md).
