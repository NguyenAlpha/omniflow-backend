# API Reference — Store

Quản lý cửa hàng (store) và thành viên. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

---

## Response envelope

Mọi API đều trả về cùng một cấu trúc bọc ngoài:

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

Khi lỗi:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "Mô tả lỗi",
    "field": "tên field nếu là lỗi validation, ngược lại null"
  }
}
```

---

## Đối tượng `StoreResponse`

Dùng chung cho mọi endpoint trả về thông tin store.

```json
{
  "id": 1,
  "name": "Chi nhánh Q1",
  "address": "123 Nguyễn Huệ, Q1, TP.HCM",
  "phone": "0901234567",
  "email": "q1@coffee.vn",
  "isActive": true,
  "createdAt": "2024-01-15T08:00:00Z",
  "updatedAt": "2024-01-15T08:00:00Z"
}
```

## Đối tượng `StoreMemberResponse`

Dùng chung cho mọi endpoint trả về thông tin thành viên store.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-...",
  "userId": 2,
  "username": "nguyen.an",
  "storeId": 1,
  "role": "ROLE_MANAGER",
  "positionTitle": "Trưởng ca",
  "joinedDate": "2024-01-15",
  "isActive": true,
  "syncVersion": 1,
  "lastModifiedAt": "2024-01-15T08:00:00Z"
}
```

---

## POST `/api/businesses/{businessId}/stores`

Tạo store mới trong một business. Chỉ **OWNER** của business mới được tạo.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Request

```json
{
  "name": "Chi nhánh Q1",
  "address": "123 Nguyễn Huệ, Q1, TP.HCM",
  "phone": "0901234567",
  "email": "q1@coffee.vn"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `name` | string | ✅ | max 200 ký tự |
| `address` | string | ❌ | — |
| `phone` | string | ❌ | 8–20 ký tự, chỉ chứa `0-9`, `+`, `-`, `(`, `)`, space |
| `email` | string | ❌ | format email hợp lệ, max 100 ký tự |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "Chi nhánh Q1",
    "address": "123 Nguyễn Huệ, Q1, TP.HCM",
    "phone": "0901234567",
    "email": "q1@coffee.vn",
    "isActive": true,
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân                                         |
|:----:|:------------|:----------------------------------------------------|
| 400  | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 403  | `ACCESS_DENIED` | Không phải OWNER của business                       |
| 404  | `BUSINESS_NOT_FOUND` | Business không tồn tại                              |
| 402  | `SUBSCRIPTION_LIMIT_EXCEEDED` | giới hạn số cửa hàng                                |
---

## GET `/api/stores`

Lấy danh sách tất cả store mà user hiện tại là thành viên.

> Không nhận body hoặc query param.

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "Chi nhánh Q1",
      "address": "123 Nguyễn Huệ, Q1, TP.HCM",
      "phone": "0901234567",
      "email": "q1@coffee.vn",
      "isActive": true,
      "createdAt": "2024-01-15T08:00:00Z",
      "updatedAt": "2024-01-15T08:00:00Z"
    }
  ],
  "error": null
}
```

> Trả về mảng rỗng `[]` nếu user chưa thuộc store nào.

---

## GET `/api/stores/{storeId}`

Lấy thông tin chi tiết một store. Yêu cầu user là **thành viên** của store đó.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "Chi nhánh Q1",
    "address": "123 Nguyễn Huệ, Q1, TP.HCM",
    "phone": "0901234567",
    "email": "q1@coffee.vn",
    "isActive": true,
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## PATCH `/api/stores/{storeId}`

Cập nhật thông tin store. Yêu cầu user là **OWNER** hoặc **MANAGER** của store.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "name": "Chi nhánh Quận 1 (mới)",
  "address": "456 Lê Lợi, Q1, TP.HCM",
  "phone": "0907654321",
  "email": "q1-new@coffee.vn"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `name` | string | ✅ | max 200 ký tự |
| `address` | string | ❌ | — |
| `phone` | string | ❌ | 8–20 ký tự, chỉ chứa `0-9`, `+`, `-`, `(`, `)`, space |
| `email` | string | ❌ | format email hợp lệ, max 100 ký tự |

### Response `200 OK`

Trả về `StoreResponse` sau khi cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ |
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## PATCH `/api/stores/{storeId}/status`

Kích hoạt hoặc vô hiệu hoá store. Chỉ **OWNER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

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

Trả về `StoreResponse` với `isActive` đã được cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `isActive` bị bỏ trống |
| 403 | `ACCESS_DENIED` | Không phải OWNER của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## GET `/api/stores/{storeId}/members`

Lấy danh sách thành viên của store. Yêu cầu user là **thành viên** của store đó.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "publicId": "a1b2c3d4-...",
      "userId": 2,
      "username": "nguyen.an",
      "storeId": 1,
      "role": "ROLE_MANAGER",
      "positionTitle": "Trưởng ca",
      "joinedDate": "2024-01-15",
      "isActive": true,
      "syncVersion": 1,
      "lastModifiedAt": "2024-01-15T08:00:00Z"
    }
  ],
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## POST `/api/stores/{storeId}/members`

Thêm user vào store. Chỉ **OWNER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Request

```json
{
  "userId": 5,
  "role": "ROLE_MANAGER",
  "positionTitle": "Trưởng ca",
  "isActive": true
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `userId` | number | ✅ | ID của user cần thêm |
| `role` | string | ✅ | `ROLE_MANAGER` hoặc `ROLE_STAFF` |
| `positionTitle` | string | ❌ | max 100 ký tự |
| `isActive` | boolean | ✅ | — |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 2,
    "publicId": "b2c3d4e5-...",
    "userId": 5,
    "username": "tran.binh",
    "storeId": 1,
    "role": "ROLE_MANAGER",
    "positionTitle": "Trưởng ca",
    "joinedDate": "2024-06-03",
    "isActive": true,
    "syncVersion": 1,
    "lastModifiedAt": "2024-06-03T09:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ |
| 403 | `ACCESS_DENIED` | Không phải OWNER của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |
| 404 | `USER_NOT_FOUND` | User cần thêm không tồn tại |
| 409 | `MEMBER_ALREADY_EXISTS` | User đã là thành viên của store |

---

## PATCH `/api/stores/{storeId}/members/{memberId}`

Cập nhật role, chức danh hoặc trạng thái của một thành viên. Chỉ **OWNER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `memberId` | number | ID của bản ghi membership |

### Request

```json
{
  "role": "ROLE_STAFF",
  "positionTitle": null,
  "isActive": true
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `role` | string | ✅ | `ROLE_MANAGER` hoặc `ROLE_STAFF` |
| `positionTitle` | string | ❌ | max 100 ký tự |
| `isActive` | boolean | ✅ | — |

### Response `200 OK`

Trả về `StoreMemberResponse` sau khi cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ |
| 403 | `ACCESS_DENIED` | Không phải OWNER của store |
| 404 | `MEMBER_NOT_FOUND` | Membership không tồn tại trong store này |

---

## DELETE `/api/stores/{storeId}/members/{memberId}`

Xoá thành viên khỏi store. Chỉ **OWNER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `memberId` | number | ID của bản ghi membership |

### Response `200 OK`

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải OWNER của store |
| 404 | `MEMBER_NOT_FOUND` | Membership không tồn tại trong store này |