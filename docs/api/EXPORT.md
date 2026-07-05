# API Reference — Export

Xuất dữ liệu ra file. Các endpoint trả về **file nhị phân** (không dùng response envelope JSON). Tất cả endpoint đều **yêu cầu JWT** và quyền **OWNER hoặc MANAGER** của store.

---

## GET `/api/stores/{storeId}/export/orders`

Xuất danh sách đơn bán hàng ra file Excel (`.xlsx`).

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Query parameters

| Parameter | Type | Bắt buộc | Mô tả |
|:----------|:-----|:--------:|:------|
| `from` | date | ❌ | Ngày bắt đầu, format `yyyy-MM-dd` |
| `to` | date | ❌ | Ngày kết thúc, format `yyyy-MM-dd` |

### Response `200 OK`

```
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="orders.xlsx"

<binary>
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## GET `/api/stores/{storeId}/export/inventory`

Xuất toàn bộ tồn kho hiện tại của store ra file Excel (`.xlsx`).

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Response `200 OK`

```
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename="inventory.xlsx"

<binary>
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |

---

## GET `/api/stores/{storeId}/export/purchase-orders/{publicId}/pdf`

Xuất một đơn nhập hàng ra file PDF.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |
| `publicId` | UUID | Public ID của đơn nhập hàng |

### Response `200 OK`

```
Content-Type: application/pdf
Content-Disposition: attachment; filename="purchase-order.pdf"

<binary>
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải OWNER hoặc MANAGER |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |
| 404 | `PURCHASE_ORDER_NOT_FOUND` | Đơn nhập không tồn tại trong store này |
