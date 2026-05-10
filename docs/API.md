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

| Method | Path | Access | Mô tả |
|:---|:---|:---|:---|
| POST | `/register` | Public | Tạo tài khoản mới |
| POST | `/login` | Public | Đăng nhập, trả JWT + store memberships |

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

## Store — `/api/stores`

| Method | Path | Access | Mô tả |
|:---|:---|:---|:---|
| POST | `/` | Authenticated | Tạo store mới; người tạo tự động thành OWNER |
| GET | `/my` | Authenticated | Lấy danh sách store của bản thân |
| GET | `/{storeId}` | Member | Lấy thông tin store |
| PUT | `/{storeId}` | Owner / Manager | Cập nhật thông tin store |

### Member Management — `/api/stores/{storeId}/members`

| Method | Path | Access | Mô tả |
|:---|:---|:---|:---|
| GET | `/` | Member | Danh sách thành viên active |
| POST | `/` | Owner | Thêm thành viên mới |
| PUT | `/{memberId}` | Owner | Đổi role hoặc positionTitle |
| DELETE | `/{memberId}` | Owner | Xóa thành viên (soft delete) |

**Ràng buộc member:**
- Không thể xóa hoặc đổi role OWNER của người khác
- OWNER tự thay đổi role của mình được phép
- Không thể thêm user đã là member

---

## Product — `/api/stores/{storeId}/products`

| Method | Path | Access | Mô tả |
|:---|:---|:---|:---|
| GET | `/` | Member | Danh sách sản phẩm; filter `?isActive=true/false` |
| GET | `/search?q=` | Member | Full-text search (PostgreSQL TSVECTOR) |
| GET | `/{publicId}` | Member | Chi tiết sản phẩm |
| POST | `/` | Owner / Manager | Tạo sản phẩm mới |
| PUT | `/{publicId}` | Owner / Manager | Cập nhật sản phẩm; tự động ghi PriceHistory nếu giá đổi |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete |

**Ràng buộc product:**
- `sku` unique per store
- Xóa product là soft delete — không mất lịch sử đơn hàng

---

## Category — `/api/stores/{storeId}/categories`

| Method | Path | Access | Mô tả |
|:---|:---|:---|:---|
| GET | `/` | Member | Danh sách category của store |
| POST | `/` | Owner / Manager | Tạo category |
| PUT | `/{publicId}` | Owner / Manager | Đổi tên / mô tả |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete |

**Ràng buộc:** `name` unique per store.

---

## Unit — `/api/stores/{storeId}/units`

| Method | Path | Access | Mô tả |
|:---|:---|:---|:---|
| GET | `/` | Member | Danh sách unit (system + store custom) |
| POST | `/` | Owner / Manager | Tạo unit cho store |
| PUT | `/{publicId}` | Owner / Manager | Cập nhật unit (chỉ store unit, không sửa system unit) |
| DELETE | `/{publicId}` | Owner / Manager | Soft delete (chỉ store unit) |

**Ràng buộc:** System unit (`store_id IS NULL`) không thể sửa hoặc xóa.

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

---

## Endpoints sẽ thêm (Phase 2)

| Domain | Base path |
|:---|:---|
| Warehouse | `/api/stores/{storeId}/warehouses` |
| Inventory | `/api/stores/{storeId}/inventory` |
| Order | `/api/stores/{storeId}/orders` |
| Return Order | `/api/stores/{storeId}/returns` |
| Purchase Order | `/api/stores/{storeId}/purchases` |
| Customer | `/api/stores/{storeId}/customers` |
| Supplier | `/api/stores/{storeId}/suppliers` |
| Payment | `/api/stores/{storeId}/payments` |
