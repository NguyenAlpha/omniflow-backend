# API Reference — Business Member (Trợ lý cấp business)

Quản lý **trợ lý cấp business** (`ROLE_BUSINESS_MANAGER`) — người quản lý mọi chi nhánh (store)
của business nhưng **không** đụng tới billing/subscription, hồ sơ business, tạo store, hay quản lý
trợ lý khác. Tất cả endpoint đều **chỉ OWNER** của business được gọi và **yêu cầu JWT**
(`Authorization: Bearer <token>`).

> Trợ lý được cấp một `UserRole` cấp business (`business_id` set, `store_id` NULL, role
> `ROLE_BUSINESS_MANAGER`) — nhờ đó với tới data mọi store trong business ở mức OWNER/MANAGER.
> Song song, một bản ghi `business_members` (roster) được tạo để lưu thông tin gia nhập.
> Trợ lý tính vào quota `max_staff` của gói (cùng bể với nhân viên store; OWNER không tính).

---

## Response envelope

```json
{ "success": true, "data": { ... }, "error": null }
```

Khi lỗi: `success=false`, `data=null`, `error={code, message, field}`.

---

## Đối tượng `BusinessMemberResponse`

```json
{
  "id": 3,
  "publicId": "a1b2c3d4-...",
  "userId": 6,
  "username": "vo.em",
  "businessId": 1,
  "role": "ROLE_BUSINESS_MANAGER",
  "joinedDate": "2025-05-01",
  "isActive": true,
  "syncVersion": 1,
  "lastModifiedAt": "2025-05-01T08:00:00Z"
}
```

> `role` có thể là `ROLE_OWNER` khi liệt kê (roster chứa cả owner), nhưng OWNER **không** sửa/xóa
> được qua các endpoint dưới đây.

---

## GET `/api/businesses/{businessId}/members`

Danh sách thành viên cấp business (OWNER + trợ lý). Chỉ **OWNER**.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 3,
      "publicId": "a1b2c3d4-...",
      "userId": 6,
      "username": "vo.em",
      "businessId": 1,
      "role": "ROLE_BUSINESS_MANAGER",
      "joinedDate": "2025-05-01",
      "isActive": true,
      "syncVersion": 1,
      "lastModifiedAt": "2025-05-01T08:00:00Z"
    }
  ],
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải OWNER của business |
| 404 | `BUSINESS_NOT_FOUND` | Business không tồn tại |

---

## POST `/api/businesses/{businessId}/members`

Thêm một user làm trợ lý cấp business. Chỉ **OWNER**.

### Request

```json
{
  "userId": 6,
  "isActive": true
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `userId` | number | ✅ | ID của user cần thêm |
| `isActive` | boolean | ✅ | — |

> Role được cố định là `ROLE_BUSINESS_MANAGER` (không nhận từ client).

### Response `201 Created`

Trả về `BusinessMemberResponse` vừa tạo.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ, hoặc user đã là thành viên của business |
| 402 | `SUBSCRIPTION_LIMIT_EXCEEDED` | Vượt quota `max_staff` của gói |
| 403 | `ACCESS_DENIED` | Không phải OWNER của business |
| 404 | `BUSINESS_NOT_FOUND` | Business không tồn tại |
| 404 | `USER_NOT_FOUND` | User cần thêm không tồn tại |

---

## PATCH `/api/businesses/{businessId}/members/{memberId}`

Bật/tắt trạng thái một trợ lý. Chỉ **OWNER**.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `memberId` | number | ID của bản ghi membership |

### Request

```json
{
  "isActive": false
}
```

| Field | Type | Bắt buộc |
|:------|:-----|:--------:|
| `isActive` | boolean | ✅ |

### Response `200 OK`

Trả về `BusinessMemberResponse` sau khi cập nhật. `isActive=false` thu hồi quyền của trợ lý.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ, hoặc cố sửa OWNER qua endpoint này |
| 403 | `ACCESS_DENIED` | Không phải OWNER của business |
| 404 | `BUSINESS_MEMBER_NOT_FOUND` | Membership không tồn tại trong business này |

---

## DELETE `/api/businesses/{businessId}/members/{memberId}`

Gỡ một trợ lý khỏi business (soft delete roster + thu hồi role). Chỉ **OWNER**.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `memberId` | number | ID của bản ghi membership |

### Response `200 OK`

```json
{ "success": true, "data": null, "error": null }
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Cố xóa OWNER qua endpoint này |
| 403 | `ACCESS_DENIED` | Không phải OWNER của business |
| 404 | `BUSINESS_MEMBER_NOT_FOUND` | Membership không tồn tại trong business này |
