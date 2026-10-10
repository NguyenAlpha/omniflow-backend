# API Reference — Product

Quản lý sản phẩm trong business. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

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

## Đối tượng `ProductResponse`

Dùng chung cho hầu hết các endpoint trả về thông tin sản phẩm.

```json
{
  "id": 1,
  "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "businessId": 1,
  "sku": "CF-ARA-001",
  "name": "Cà phê Arabica",
  "description": "Cà phê hạt rang xay nguyên chất",
  "categoryId": 2,
  "categoryPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "categoryName": "Cà phê",
  "unitId": 1,
  "unitPublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "unitName": "Kilogram",
  "unitAbbreviation": "kg",
  "costPrice": 300000.00,
  "sellingPrice": 500000.00,
  "totalStock": 50.00,
  "minStockLevel": 10,
  "isActive": true,
  "syncVersion": 1,
  "lastModifiedAt": "2024-01-15T08:00:00Z",
  "createdAt": "2024-01-15T08:00:00Z",
  "updatedAt": "2024-01-15T08:00:00Z"
}
```

---

## GET `/api/businesses/{businessId}/products/limit`

Kiểm tra business còn được tạo thêm sản phẩm theo giới hạn gói subscription (`maxProducts`) hay không. UI gọi trước khi mở form tạo sản phẩm. Yêu cầu quyền **OWNER hoặc MANAGER** (giống `POST /products`).

Endpoint chỉ đọc, không khóa subscription — kết quả có thể cũ đi khi submit. `POST /products` vẫn kiểm tra lại và trả `402 SUBSCRIPTION_LIMIT_EXCEEDED` nếu vượt giới hạn.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "canCreate": false,
    "currentProducts": 50,
    "maxProducts": 50
  },
  "error": null
}
```

| Field | Type | Mô tả |
|:------|:-----|:------|
| `canCreate` | boolean | `true` nếu `maxProducts = null` hoặc `currentProducts < maxProducts` |
| `currentProducts` | number | Số sản phẩm chưa bị xóa (soft delete) của business |
| `maxProducts` | number \| null | Giới hạn của gói hiện tại; `null` = không giới hạn |

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER/MANAGER của business |
| 404 | `SUBSCRIPTION_NOT_FOUND` | Business chưa có subscription (không xảy ra bình thường) |

---

## GET `/api/businesses/{businessId}/products/sku/{sku}`

Tra cứu sản phẩm theo SKU chính xác. Dùng cho tính năng quét mã vạch — client decode barcode ra SKU rồi gọi endpoint này để lấy thông tin sản phẩm. Yêu cầu là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |
| `sku` | string | SKU chính xác của sản phẩm (URL-encoded) |

### Response `200 OK`

Trả về `ProductResponse` của sản phẩm tìm thấy.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của business |
| 404 | `PRODUCT_NOT_FOUND` | Không tìm thấy sản phẩm với SKU này |

---

## GET `/api/businesses/{businessId}/products`

Lấy toàn bộ danh sách sản phẩm (không phân trang). Yêu cầu là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `isActive` | boolean | ❌ | Lọc theo trạng thái. Bỏ trống để lấy tất cả |

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "businessId": 1,
      "sku": "CF-ARA-001",
      "name": "Cà phê Arabica",
      "description": "Cà phê hạt rang xay nguyên chất",
      "categoryId": 2,
      "categoryPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "categoryName": "Cà phê",
      "unitId": 1,
      "unitPublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
      "unitName": "Kilogram",
      "unitAbbreviation": "kg",
      "costPrice": 300000.00,
      "sellingPrice": 500000.00,
      "totalStock": 50.00,
      "minStockLevel": 10,
      "isActive": true,
      "syncVersion": 1,
      "lastModifiedAt": "2024-01-15T08:00:00Z",
      "createdAt": "2024-01-15T08:00:00Z",
      "updatedAt": "2024-01-15T08:00:00Z"
    }
  ],
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của business |

---

## GET `/api/businesses/{businessId}/products/search`

Tìm kiếm sản phẩm có phân trang và lọc. Yêu cầu là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `q` | string | ❌ | Tìm kiếm theo tên hoặc SKU |
| `isActive` | boolean | ❌ | Lọc theo trạng thái. Bỏ trống để lấy tất cả |
| `categoryPublicId` | UUID | ❌ | Lọc theo danh mục |
| `page` | number | ❌ | Trang hiện tại (bắt đầu từ `0`), mặc định `0` |
| `size` | number | ❌ | Số bản ghi mỗi trang, mặc định `20` |
| `sortBy` | string | ❌ | Trường sắp xếp: `name` hoặc `updatedAt` (mặc định) |
| `sort` | string | ❌ | Chiều sắp xếp: `asc` hoặc `desc` (mặc định) |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        "businessId": 1,
        "sku": "CF-ARA-001",
        "name": "Cà phê Arabica",
        "description": "Cà phê hạt rang xay nguyên chất",
        "categoryId": 2,
        "categoryPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
        "categoryName": "Cà phê",
        "unitId": 1,
        "unitPublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
        "unitName": "Kilogram",
        "unitAbbreviation": "kg",
        "costPrice": 300000.00,
        "sellingPrice": 500000.00,
        "totalStock": 50.00,
        "minStockLevel": 10,
        "isActive": true,
        "syncVersion": 1,
        "lastModifiedAt": "2024-01-15T08:00:00Z",
        "createdAt": "2024-01-15T08:00:00Z",
        "updatedAt": "2024-01-15T08:00:00Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của business |

---

## GET `/api/businesses/{businessId}/products/{publicId}`

Lấy chi tiết sản phẩm kèm lịch sử thay đổi giá. Yêu cầu là **thành viên** của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của sản phẩm |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "productResponse": {
      "id": 1,
      "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "businessId": 1,
      "sku": "CF-ARA-001",
      "name": "Cà phê Arabica",
      "description": "Cà phê hạt rang xay nguyên chất",
      "categoryId": 2,
      "categoryPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "categoryName": "Cà phê",
      "unitId": 1,
      "unitPublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
      "unitName": "Kilogram",
      "unitAbbreviation": "kg",
      "costPrice": 300000.00,
      "sellingPrice": 500000.00,
      "totalStock": 50.00,
      "minStockLevel": 10,
      "isActive": true,
      "syncVersion": 2,
      "lastModifiedAt": "2024-02-01T09:00:00Z",
      "createdAt": "2024-01-15T08:00:00Z",
      "updatedAt": "2024-02-01T09:00:00Z"
    },
    "priceHistory": [
      {
        "id": 1,
        "businessId": 1,
        "productPublicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
        "productName": "Cà phê Arabica",
        "oldCostPrice": 280000.00,
        "newCostPrice": 300000.00,
        "oldSellingPrice": 450000.00,
        "newSellingPrice": 500000.00,
        "changedByUsername": "nguyen.an",
        "changedAt": "2024-02-01T09:00:00Z"
      }
    ]
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải thành viên của business |
| 404 | `NOT_FOUND` | Không tìm thấy sản phẩm |

---

## POST `/api/businesses/{businessId}/products`

Tạo sản phẩm mới. Chỉ **OWNER** hoặc **MANAGER** của business mới được tạo.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |

### Request

```json
{
  "sku": "CF-ARA-001",
  "name": "Cà phê Arabica",
  "description": "Cà phê hạt rang xay nguyên chất",
  "categoryPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "unitPublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "costPrice": 300000.00,
  "sellingPrice": 500000.00,
  "minStockLevel": 10,
  "isActive": true
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `sku` | string | ✅ | Không được để trống, tối đa 50 ký tự (được trim trước khi lưu) |
| `name` | string | ✅ | Không được để trống, tối đa 200 ký tự (được trim trước khi lưu) |
| `description` | string | ❌ | Tối đa 4000 ký tự |
| `categoryPublicId` | UUID | ❌ | |
| `unitPublicId` | UUID | ✅ | |
| `costPrice` | number | ✅ | Tối thiểu `0.00` |
| `sellingPrice` | number | ✅ | Tối thiểu `0.00` |
| `minStockLevel` | number | ✅ | Tối thiểu `0` |
| `isActive` | boolean | ✅ | |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 1,
    "sku": "CF-ARA-001",
    "name": "Cà phê Arabica",
    "description": "Cà phê hạt rang xay nguyên chất",
    "categoryId": 2,
    "categoryPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "categoryName": "Cà phê",
    "unitId": 1,
    "unitPublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "unitName": "Kilogram",
    "unitAbbreviation": "kg",
    "costPrice": 300000.00,
    "sellingPrice": 500000.00,
    "totalStock": 0.00,
    "minStockLevel": 10,
    "isActive": true,
    "syncVersion": 1,
    "lastModifiedAt": "2024-01-15T08:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của business |
| 404 | `NOT_FOUND` | Không tìm thấy category hoặc unit |

---

## POST `/api/businesses/{businessId}/products/import`

Import sản phẩm hàng loạt từ file CSV. Yêu cầu quyền **OWNER hoặc MANAGER** (OWNER/BUSINESS_MANAGER
cấp business, hoặc MANAGER của một store trong business).

Mỗi dòng được xử lý độc lập: dòng lỗi bị bỏ qua kèm thông báo, **không rollback** các dòng hợp lệ.

### Request

`Content-Type: multipart/form-data`, field `file` là file CSV (chuẩn RFC 4180 — tên có dấu phẩy /
ngoặc kép phải đặt trong ngoặc kép). Dòng đầu là header và luôn bị bỏ qua. Tối đa **1.000 dòng dữ liệu**
mỗi file — phần vượt bị bỏ qua.

Thứ tự cột:

| # | Cột | Bắt buộc | Ràng buộc |
|:-:|:----|:--------:|:----------|
| 1 | `sku` | ✅ | max 50 ký tự, unique trong business — trùng thì dòng bị bỏ qua |
| 2 | `name` | ✅ | max 200 ký tự |
| 3 | `description` | ❌ | |
| 4 | `categoryName` | ❌ | tên danh mục đã có trong business; để trống = không có danh mục |
| 5 | `unitName` | ✅ | tên đơn vị tính — system unit hoặc unit của business |
| 6 | `costPrice` | ✅ | số, không âm |
| 7 | `sellingPrice` | ✅ | số, không âm |
| 8 | `minStockLevel` | ✅ | số, không âm |
| 9 | `isActive` | ❌ | `true`/`false`; bỏ trống cột = `true` |

Sản phẩm vượt giới hạn gói (`maxProducts`) bị bỏ qua — các dòng hợp lệ trước đó vẫn được lưu.

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "imported": 2,
    "skipped": 1,
    "errors": [
      "Row 3: SKU 'CF-001' already exists, skipped"
    ]
  },
  "error": null
}
```

| Field | Type | Mô tả |
|:------|:-----|:------|
| `imported` | number | Số sản phẩm đã tạo |
| `skipped` | number | Số dòng bị bỏ qua |
| `errors` | string[] | Lý do theo từng dòng (`Row <n>: ...`, `n` tính cả dòng header) và lỗi cấp file (file rỗng, vượt 1.000 dòng) |

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của business |
| 429 | `RATE_LIMIT_EXCEEDED` | Quá 5 lần import / 10 phút mỗi user (`RATE_LIMIT_IMPORT_PRODUCTS_USER_*`) — kèm header `Retry-After` (giây phải chờ); xem [RATE_LIMITING.md](../RATE_LIMITING.md) |

> Lỗi theo từng dòng **không** trả HTTP lỗi — xem `errors` trong response `200`.

---

## PUT `/api/businesses/{businessId}/products/{publicId}`

Cập nhật thông tin sản phẩm. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

> Nếu `costPrice` hoặc `sellingPrice` thay đổi, một bản ghi lịch sử giá mới sẽ được tạo tự động.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của sản phẩm |

### Request

Cùng cấu trúc với `POST`, xem bảng fields ở trên.

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 1,
    "sku": "CF-ARA-001",
    "name": "Cà phê Arabica (Updated)",
    "description": "Cà phê hạt rang xay nguyên chất",
    "categoryId": 2,
    "categoryPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "categoryName": "Cà phê",
    "unitId": 1,
    "unitPublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "unitName": "Kilogram",
    "unitAbbreviation": "kg",
    "costPrice": 320000.00,
    "sellingPrice": 520000.00,
    "totalStock": 50.00,
    "minStockLevel": 10,
    "isActive": true,
    "syncVersion": 2,
    "lastModifiedAt": "2024-02-01T09:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-02-01T09:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của business |
| 404 | `NOT_FOUND` | Không tìm thấy sản phẩm, category hoặc unit |

---

## PATCH `/api/businesses/{businessId}/products/{publicId}/status`

Bật/tắt trạng thái hoạt động của sản phẩm. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của sản phẩm |

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

```json
{
  "success": true,
  "data": {
    "id": 1,
    "publicId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "businessId": 1,
    "sku": "CF-ARA-001",
    "name": "Cà phê Arabica",
    "description": "Cà phê hạt rang xay nguyên chất",
    "categoryId": 2,
    "categoryPublicId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "categoryName": "Cà phê",
    "unitId": 1,
    "unitPublicId": "c3d4e5f6-a7b8-9012-cdef-123456789012",
    "unitName": "Kilogram",
    "unitAbbreviation": "kg",
    "costPrice": 300000.00,
    "sellingPrice": 500000.00,
    "totalStock": 50.00,
    "minStockLevel": 10,
    "isActive": false,
    "syncVersion": 2,
    "lastModifiedAt": "2024-02-01T09:00:00Z",
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-02-01T09:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `isActive` bị bỏ trống |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của business |
| 404 | `NOT_FOUND` | Không tìm thấy sản phẩm |

---

## DELETE `/api/businesses/{businessId}/products/{publicId}`

Xoá sản phẩm. Chỉ **OWNER** hoặc **MANAGER** mới được thực hiện.

> Sản phẩm còn tồn kho (`totalStock > 0`) không thể xóa — phải xuất/điều chỉnh hết
> tồn trước, tránh lệch giữa tồn kho và catalog.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:------|
| `businessId` | number | ID của business |
| `publicId` | UUID | Public ID của sản phẩm |

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
| 400 | `VALIDATION_ERROR` | Sản phẩm còn tồn kho (`totalStock > 0`) |
| 401 | `UNAUTHORIZED` | Không có hoặc JWT hết hạn |
| 403 | `FORBIDDEN` | Không phải OWNER hoặc MANAGER của business |
| 404 | `NOT_FOUND` | Không tìm thấy sản phẩm |
