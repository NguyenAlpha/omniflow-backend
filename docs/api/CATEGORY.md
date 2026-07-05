# API Reference — Category

Quản lý danh mục sản phẩm theo phạm vi business. Tất cả endpoint đều **yêu cầu JWT**.

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

## CategoryResponse

Dùng chung cho tất cả endpoint trả về danh mục.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `id` | number | Internal ID |
| `publicId` | UUID | ID công khai dùng trong API |
| `businessId` | number | ID của business sở hữu |
| `name` | string | Tên danh mục |
| `description` | string \| null | Mô tả danh mục |
| `syncVersion` | number | Phiên bản để đồng bộ offline |
| `lastModifiedAt` | ISO 8601 | Thời điểm cập nhật gần nhất |
| `createdAt` | ISO 8601 | Thời điểm tạo |
| `updatedAt` | ISO 8601 | Thời điểm cập nhật gần nhất trong DB |

---

## GET `/api/businesses/{businessId}/categories`

Lấy danh sách tất cả danh mục của một business.

**Quyền:** Thành viên của business (`isMember`).

### Path Parameters

| Tham số | Type | Mô tả |
|:--------|:-----|:------|
| `businessId` | number | ID của business |

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "businessId": 10,
      "name": "Đồ uống",
      "description": "Các loại thức uống",
      "syncVersion": 1,
      "lastModifiedAt": "2024-01-15T08:30:00Z",
      "createdAt": "2024-01-10T07:00:00Z",
      "updatedAt": "2024-01-15T08:30:00Z"
    }
  ],
  "error": null
}
```

### Lỗi

| HTTP | Code | Nguyên nhân |
|:-----|:-----|:------------|
| 401 | `UNAUTHORIZED` | Thiếu hoặc JWT không hợp lệ |
| 403 | `FORBIDDEN` | Không phải thành viên của business |
| 404 | `BUSINESS_NOT_FOUND` | `businessId` không tồn tại |

---

## POST `/api/businesses/{businessId}/categories`

Tạo danh mục mới.

**Quyền:** Owner hoặc Manager của business (`isOwnerOrManager`).

### Path Parameters

| Tham số | Type | Mô tả |
|:--------|:-----|:------|
| `businessId` | number | ID của business |

### Request

```json
{
  "name": "Đồ uống",
  "description": "Các loại thức uống"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `name` | string | ✅ | Không rỗng, max 100 ký tự |
| `description` | string | ❌ | max 2000 ký tự |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 10,
    "name": "Đồ uống",
    "description": "Các loại thức uống",
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:30:00Z",
    "createdAt": "2024-01-15T08:30:00Z",
    "updatedAt": "2024-01-15T08:30:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | Code | Nguyên nhân |
|:-----|:-----|:------------|
| 400 | `VALIDATION_ERROR` | `name` không hợp lệ hoặc `description` vượt quá 2000 ký tự |
| 401 | `UNAUTHORIZED` | Thiếu hoặc JWT không hợp lệ |
| 403 | `FORBIDDEN` | Không đủ quyền (không phải Owner/Manager) |
| 404 | `BUSINESS_NOT_FOUND` | `businessId` không tồn tại |

---

## PUT `/api/businesses/{businessId}/categories/{publicId}`

Cập nhật toàn bộ thông tin danh mục.

**Quyền:** Owner hoặc Manager của business (`isOwnerOrManager`).

### Path Parameters

| Tham số | Type | Mô tả |
|:--------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của danh mục |

### Request

```json
{
  "name": "Đồ uống",
  "description": "Các loại thức uống và nước giải khát"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `name` | string | ✅ | Không rỗng, max 100 ký tự |
| `description` | string | ❌ | max 2000 ký tự |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 10,
    "name": "Đồ uống",
    "description": "Các loại thức uống và nước giải khát",
    "syncVersion": 2,
    "lastModifiedAt": "2024-01-16T10:00:00Z",
    "createdAt": "2024-01-15T08:30:00Z",
    "updatedAt": "2024-01-16T10:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | Code | Nguyên nhân |
|:-----|:-----|:------------|
| 400 | `VALIDATION_ERROR` | `name` không hợp lệ hoặc `description` vượt quá 2000 ký tự |
| 401 | `UNAUTHORIZED` | Thiếu hoặc JWT không hợp lệ |
| 403 | `FORBIDDEN` | Không đủ quyền (không phải Owner/Manager) |
| 404 | `CATEGORY_NOT_FOUND` | `publicId` không tồn tại hoặc đã bị xóa |

---

## DELETE `/api/businesses/{businessId}/categories/{publicId}`

Xóa mềm danh mục.

**Quyền:** Owner hoặc Manager của business (`isOwnerOrManager`).

### Path Parameters

| Tham số | Type | Mô tả |
|:--------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của danh mục |

### Response `200 OK`

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

### Lỗi

| HTTP | Code | Nguyên nhân |
|:-----|:-----|:------------|
| 401 | `UNAUTHORIZED` | Thiếu hoặc JWT không hợp lệ |
| 403 | `FORBIDDEN` | Không đủ quyền (không phải Owner/Manager) |
| 404 | `CATEGORY_NOT_FOUND` | `publicId` không tồn tại hoặc đã bị xóa |
