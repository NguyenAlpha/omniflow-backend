# API Reference

Base URL: `/api`

**Access levels:**
- `Public` — không cần token
- `Authenticated` — cần JWT hợp lệ, không yêu cầu role cụ thể
- `Member` — phải là thành viên của store (OWNER / MANAGER / STAFF)
- `Owner / Manager` — phải có role OWNER hoặc MANAGER trong store
- `Owner` — phải có role OWNER trong store
- `SUPER_ADMIN` — chỉ global admin

---

## Auth — `/api/auth`

| Method | Path        | Access | Mô tả                                  |
|:-------|:------------|:-------|:---------------------------------------|
| POST   | `/register` | Public | Tạo tài khoản mới                      |
| POST   | `/login`    | Public | Đăng nhập, trả JWT + store memberships |

**Request / Response:**

```
POST /api/auth/register
Body: { username, email, password, fullName, phone? }
Response: AuthResponse { accessToken, tokenType, expiresIn, user, storeMemberships }

POST /api/auth/login
Body: { usernameOrEmail, password }
Response: AuthResponse { accessToken, tokenType, expiresIn, user, storeMemberships }
```

---

## User — `/api/users`

| Method | Path           | Access        | Mô tả                    |
|:-------|:---------------|:--------------|:-------------------------|
| GET    | `/me`          | Authenticated | Lấy profile của bản thân |
| PATCH  | `/me`          | Authenticated | Cập nhật fullName, phone |
| PATCH  | `/me/password` | Authenticated | Đổi mật khẩu             |

**Request / Response:**

```
GET /api/users/me
Response: UserSummaryResponse { id, username, email, fullName, phone, isActive }

PATCH /api/users/me
Body: { username, email, fullName, phone? }
Response: UserSummaryResponse

PATCH /api/users/me/password
Body: { currentPassword, newPassword }   — newPassword tối thiểu 6 ký tự
Response: null
```

---

## Admin — User Management — `/api/admin/users`

> Tất cả endpoint trong section này yêu cầu role **SUPER_ADMIN**.

| Method | Path               | Access      | Mô tả                                     |
|:-------|:-------------------|:------------|:------------------------------------------|
| GET    | `/`                | SUPER_ADMIN | Danh sách user (có phân trang + tìm kiếm) |
| PATCH  | `/{userId}`        | SUPER_ADMIN | Cập nhật fullName, phone của user bất kỳ  |
| PATCH  | `/{userId}/status` | SUPER_ADMIN | Đổi trạng thái active / inactive          |
| DELETE | `/{userId}`        | SUPER_ADMIN | Soft delete user                          |

**Request / Response:**

```
GET /api/admin/users?q=&page=0&size=20
  q    — tìm theo username / email / fullName (optional)
  page — số trang, bắt đầu từ 0 (default: 0)
  size — số bản ghi mỗi trang (default: 20)
Response: PagedResult<UserAdminResponse> { content, page, size, totalElements, totalPages }
  UserAdminResponse { id, username, email, fullName, phone, isActive, createdAt, deletedAt }

PUT /api/admin/users/{userId}
Body: { username, email, fullName, phone? }
Response: UserAdminResponse

PUT /api/admin/users/{userId}/status
Body: { "isActive": true | false }
Response: UserAdminResponse

DELETE /api/admin/users/{userId}
Response: null   — soft delete (set deletedAt + isActive = false); user đã xóa không hiện trong GET
```

---

## Store — `/api/stores`

| Method | Path                | Access          | Mô tả                                                  |
|:-------|:--------------------|:----------------|:-------------------------------------------------------|
| POST   | `/`                 | Authenticated   | Tạo store mới; người tạo tự động thành OWNER           |
| GET    | `/`                 | Authenticated   | Lấy danh sách store của bản thân, (admin = lấy tất cả) |
| GET    | `/{storeId}`        | Member          | Lấy thông tin store                                    |
| PATCH  | `/{storeId}`        | Owner / Manager | Cập nhật thông tin store                               |
| PATCH  | `/{storeId}/status` | Owner           | Đổi trạng thái                                         |

### Member Management — `/api/stores/{storeId}/members`

| Method | Path          | Access | Mô tả                        |
|:-------|:--------------|:-------|:-----------------------------|
| GET    | `/`           | Member | Danh sách thành viên active  |
| POST   | `/`           | Owner  | Thêm thành viên mới          |
| PUT    | `/{memberId}` | Owner  | Đổi role hoặc positionTitle  |
| DELETE | `/{memberId}` | Owner  | Xóa thành viên (soft delete) |

**Ràng buộc member:**
- Không thể xóa hoặc đổi role OWNER của người khác
- OWNER tự thay đổi role của mình được phép
- Không thể thêm user đã là member

---

## Product — `/api/stores/{storeId}/products`

| Method | Path          | Access          | Mô tả                                                   |
|:-------|:--------------|:----------------|:--------------------------------------------------------|
| GET    | `/`           | Member          | Danh sách sản phẩm; filter `?isActive=true/false`       |
| GET    | `/search?q=`  | Member          | Full-text search (PostgreSQL TSVECTOR)                  |
| GET    | `/{publicId}` | Member          | Chi tiết sản phẩm                                       |
| POST   | `/`           | Owner / Manager | Tạo sản phẩm mới                                        |
| PUT    | `/{publicId}` | Owner / Manager | Cập nhật sản phẩm; tự động ghi PriceHistory nếu giá đổi |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete                                             |

**Ràng buộc product:**
- `sku` unique per store
- Xóa product là soft delete — không mất lịch sử đơn hàng

---

## Category — `/api/stores/{storeId}/categories`

| Method | Path          | Access          | Mô tả                        |
|:-------|:--------------|:----------------|:-----------------------------|
| GET    | `/`           | Member          | Danh sách category của store |
| POST   | `/`           | Owner / Manager | Tạo category                 |
| PUT    | `/{publicId}` | Owner / Manager | Đổi tên / mô tả              |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete                  |

**Ràng buộc:** `name` unique per store.

---

## Unit — `/api/stores/{storeId}/units`

| Method | Path          | Access          | Mô tả                                                 |
|:-------|:--------------|:----------------|:------------------------------------------------------|
| GET    | `/`           | Member          | Danh sách unit (system + store custom)                |
| POST   | `/`           | Owner / Manager | Tạo unit cho store                                    |
| PATCH  | `/{publicId}` | Owner / Manager | Cập nhật unit (chỉ store unit, không sửa system unit) |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete (chỉ store unit)                          |

**Ràng buộc:** System unit (`store_id IS NULL`) không thể sửa hoặc xóa.

---

## Warehouse — `/api/stores/{storeId}/warehouses`

| Method | Path          | Access          | Mô tả         |
|:-------|:--------------|:----------------|:--------------|
| GET    | `/`           | Member          | Danh sách kho |
| GET    | `/{publicId}` | Member          | Chi tiết kho  |
| POST   | `/`           | Owner / Manager | Tạo kho mới   |
| PUT    | `/{publicId}` | Owner / Manager | Cập nhật kho  |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete   |

**Ràng buộc:** `name` unique per store.

---

## Inventory — `/api/stores/{storeId}/inventory`

| Method | Path            | Access          | Mô tả                                                            |
|:-------|:----------------|:----------------|:-----------------------------------------------------------------|
| GET    | `/`             | Member          | Tồn kho toàn store; filter `?warehousePublicId=` để lọc theo kho |
| GET    | `/transactions` | Member          | Lịch sử nhập/xuất kho                                            |
| POST   | `/adjust`       | Owner / Manager | Điều chỉnh tồn kho thủ công (ADJUSTMENT transaction)             |

**Lưu ý:** `quantity` trong adjust có thể âm (giảm tồn kho) hoặc dương (tăng tồn kho).

---

## Customer — `/api/stores/{storeId}/customers`

| Method | Path          | Access          | Mô tả                        |
|:-------|:--------------|:----------------|:-----------------------------|
| GET    | `/`           | Member          | Danh sách khách hàng         |
| GET    | `/search?q=`  | Member          | Tìm kiếm theo tên / mã / SĐT |
| GET    | `/{publicId}` | Member          | Chi tiết khách hàng          |
| POST   | `/`           | Owner / Manager | Tạo khách hàng               |
| PUT    | `/{publicId}` | Owner / Manager | Cập nhật thông tin           |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete                  |

**Ràng buộc:** `code` unique per store. `debtBalance` chỉ thay đổi qua Order / Payment / ReturnOrder — không cập nhật trực tiếp.

---

## Supplier — `/api/stores/{storeId}/suppliers`

| Method | Path          | Access          | Mô tả                        |
|:-------|:--------------|:----------------|:-----------------------------|
| GET    | `/`           | Member          | Danh sách nhà cung cấp       |
| GET    | `/search?q=`  | Member          | Tìm kiếm theo tên / mã / SĐT |
| GET    | `/{publicId}` | Member          | Chi tiết nhà cung cấp        |
| POST   | `/`           | Owner / Manager | Tạo nhà cung cấp             |
| PUT    | `/{publicId}` | Owner / Manager | Cập nhật thông tin           |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete                  |

**Ràng buộc:** `code` unique per store.

---

## Order — `/api/stores/{storeId}/orders`

| Method | Path                   | Access          | Mô tả                                     |
|:-------|:-----------------------|:----------------|:------------------------------------------|
| GET    | `/`                    | Member          | Danh sách đơn hàng (không có items)       |
| GET    | `/{publicId}`          | Member          | Chi tiết đơn hàng kèm items               |
| POST   | `/`                    | Owner / Manager | Tạo đơn hàng mới (status: PENDING)        |
| PUT    | `/{publicId}/complete` | Owner / Manager | Hoàn thành đơn — cộng debt vào khách hàng |
| PUT    | `/{publicId}/cancel`   | Owner / Manager | Hủy đơn — hoàn lại tồn kho                |

**Luồng trạng thái:** `PENDING → COMPLETED | CANCELLED`

**Ràng buộc:**
- `orderCode` unique per store
- Tạo đơn → trừ tồn kho ngay (throw nếu không đủ stock)
- Complete → cộng `debtAmount` vào `customer.debtBalance`
- Cancel → hoàn lại tồn kho (InventoryTransaction IN)

---

## Return Order — `/api/stores/{storeId}/returns`

| Method | Path                   | Access          | Mô tả                                 |
|:-------|:-----------------------|:----------------|:--------------------------------------|
| GET    | `/`                    | Member          | Danh sách đơn trả hàng                |
| GET    | `/{publicId}`          | Member          | Chi tiết đơn trả kèm items            |
| POST   | `/`                    | Owner / Manager | Tạo đơn trả (status: PENDING)         |
| PUT    | `/{publicId}/complete` | Owner / Manager | Hoàn thành trả — hoàn kho + giảm debt |
| PUT    | `/{publicId}/cancel`   | Owner / Manager | Hủy đơn trả                           |

**Ràng buộc:**
- `returnCode` unique per store
- Complete → restore inventory + trừ `customer.debtBalance`

---

## Purchase Order — `/api/stores/{storeId}/purchases`

| Method | Path                  | Access          | Mô tả                                         |
|:-------|:----------------------|:----------------|:----------------------------------------------|
| GET    | `/`                   | Member          | Danh sách đơn nhập hàng                       |
| GET    | `/{publicId}`         | Member          | Chi tiết đơn nhập kèm items                   |
| POST   | `/`                   | Owner / Manager | Tạo đơn nhập (status: PENDING)                |
| PUT    | `/{publicId}/receive` | Owner / Manager | Nhận hàng — nhập kho + cộng debt nhà cung cấp |
| PUT    | `/{publicId}/cancel`  | Owner / Manager | Hủy đơn nhập                                  |

**Luồng trạng thái:** `PENDING → RECEIVED | CANCELLED`

**Ràng buộc:**
- `orderCode` unique per store
- Receive → thêm tồn kho (InventoryTransaction IN) + cộng `debtAmount` vào `supplier.debtBalance`

---

## Payment — `/api/stores/{storeId}/payments`

| Method | Path          | Access          | Mô tả               |
|:-------|:--------------|:----------------|:--------------------|
| GET    | `/`           | Member          | Danh sách thu/chi   |
| GET    | `/{publicId}` | Member          | Chi tiết payment    |
| POST   | `/`           | Owner / Manager | Ghi nhận thanh toán |

**Ràng buộc:**
- Payment phải thuộc đúng một trong hai: `customerPublicId` hoặc `supplierPublicId` (không được cả hai, không được để trống cả hai)
- Customer payment → trừ `customer.debtBalance`
- Supplier payment → trừ `supplier.debtBalance`
- Payment là immutable — không có UPDATE hay DELETE

---

## Response format chuẩn

```json
// Success
{
  "success": true,
  "data": { ... },
  "error": null
}

// Failure
{
  "success": false,
  "data": null,
  "error": {
    "code": "STORE_NOT_FOUND",
    "message": "Store not found",
    "field": null
  }
}
```

Xem chi tiết mapping lỗi: [ERROR_LIFECYCLE.md](ERROR_LIFECYCLE.md)
