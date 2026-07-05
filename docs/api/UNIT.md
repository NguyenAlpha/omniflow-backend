# API Reference — Unit

Quản lý đơn vị tính (kg, lít, cái, ...) theo phạm vi business. Tất cả endpoint đều **yêu cầu JWT**.

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

## UnitResponse

Dùng chung cho tất cả endpoint trả về đơn vị tính.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `id` | number | Internal ID |
| `publicId` | UUID | ID công khai dùng trong API |
| `businessId` | number | ID của business sở hữu |
| `name` | string | Tên đơn vị (ví dụ: Kilogram) |
| `abbreviation` | string | Ký hiệu viết tắt (ví dụ: kg) |
| `syncVersion` | number | Phiên bản để đồng bộ offline |
| `lastModifiedAt` | ISO 8601 | Thời điểm cập nhật gần nhất |

---

## GET `/api/businesses/{businessId}/units`

Lấy danh sách tất cả đơn vị tính của một business.

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
      "name": "Kilogram",
      "abbreviation": "kg",
      "syncVersion": 1,
      "lastModifiedAt": "2024-01-15T08:30:00Z"
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

## POST `/api/businesses/{businessId}/units`

Tạo đơn vị tính mới.

**Quyền:** Owner hoặc Manager của business (`isOwnerOrManager`).

### Path Parameters

| Tham số | Type | Mô tả |
|:--------|:-----|:------|
| `businessId` | number | ID của business |

### Request

```json
{
  "name": "Kilogram",
  "abbreviation": "kg"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `name` | string | ✅ | Không rỗng, max 50 ký tự |
| `abbreviation` | string | ✅ | Không rỗng, max 10 ký tự |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 10,
    "name": "Kilogram",
    "abbreviation": "kg",
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:30:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | Code | Nguyên nhân |
|:-----|:-----|:------------|
| 400 | `VALIDATION_ERROR` | `name` hoặc `abbreviation` không hợp lệ |
| 401 | `UNAUTHORIZED` | Thiếu hoặc JWT không hợp lệ |
| 403 | `FORBIDDEN` | Không đủ quyền (không phải Owner/Manager) |
| 404 | `BUSINESS_NOT_FOUND` | `businessId` không tồn tại |

---

## PATCH `/api/businesses/{businessId}/units/{publicId}`

Cập nhật thông tin đơn vị tính.

**Quyền:** Owner hoặc Manager của business (`isOwnerOrManager`).

### Path Parameters

| Tham số | Type | Mô tả |
|:--------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của đơn vị tính |

### Request

```json
{
  "name": "Kilogram",
  "abbreviation": "kg"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `name` | string | ✅ | Không rỗng, max 50 ký tự |
| `abbreviation` | string | ✅ | Không rỗng, max 10 ký tự |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 10,
    "name": "Kilogram",
    "abbreviation": "kg",
    "syncVersion": 2,
    "lastModifiedAt": "2024-01-16T10:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | Code | Nguyên nhân |
|:-----|:-----|:------------|
| 400 | `VALIDATION_ERROR` | `name` hoặc `abbreviation` không hợp lệ |
| 401 | `UNAUTHORIZED` | Thiếu hoặc JWT không hợp lệ |
| 403 | `FORBIDDEN` | Không đủ quyền (không phải Owner/Manager) |
| 404 | `UNIT_NOT_FOUND` | `publicId` không tồn tại hoặc đã bị xóa |

---

## DELETE `/api/businesses/{businessId}/units/{publicId}`

Xóa mềm đơn vị tính.

**Quyền:** Owner hoặc Manager của business (`isOwnerOrManager`).

### Path Parameters

| Tham số | Type | Mô tả |
|:--------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của đơn vị tính |

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
| 404 | `UNIT_NOT_FOUND` | `publicId` không tồn tại hoặc đã bị xóa |
