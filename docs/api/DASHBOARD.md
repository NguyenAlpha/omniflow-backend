# API Reference — Dashboard

Lấy dữ liệu tổng hợp cho trang tổng quan của store. Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

---

## Response envelope

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

---

## Đối tượng `DashboardResponse`

```json
{
  "kpi": {
    "revenueThisMonth": 12500000.00,
    "revenueLastMonth": 10200000.00,
    "collectedThisMonth": 11000000.00,
    "collectedLastMonth": 9500000.00,
    "ordersThisMonth": 48,
    "ordersLastMonth": 39,
    "totalCustomers": 125
  },
  "salesChart": [
    { "month": "2024-01", "revenue": 8500000.00, "orderCount": 32 },
    { "month": "2024-02", "revenue": 9100000.00, "orderCount": 36 },
    { "month": "2024-03", "revenue": 10200000.00, "orderCount": 39 },
    { "month": "2024-04", "revenue": 11300000.00, "orderCount": 43 },
    { "month": "2024-05", "revenue": 10800000.00, "orderCount": 41 },
    { "month": "2024-06", "revenue": 12500000.00, "orderCount": 48 }
  ],
  "lowStockProducts": [
    {
      "productName": "Cà phê sữa lon",
      "sku": "CF-SUA-001",
      "totalStock": 3,
      "minStockLevel": 10
    }
  ],
  "recentOrders": [
    {
      "orderCode": "ORD-2024-001",
      "customerName": "Nguyễn Văn A",
      "totalAmount": 250000.00,
      "status": "COMPLETED",
      "createdAt": "2024-06-15T09:30:00Z"
    }
  ]
}
```

### `kpi`

| Field | Type | Mô tả |
|:------|:-----|:------|
| `revenueThisMonth` | number | Tổng doanh thu tháng hiện tại |
| `revenueLastMonth` | number | Tổng doanh thu tháng trước (để so sánh) |
| `collectedThisMonth` | number | Doanh thu thực thu tháng hiện tại (đã thanh toán) |
| `collectedLastMonth` | number | Doanh thu thực thu tháng trước |
| `ordersThisMonth` | number | Số đơn hàng tháng hiện tại |
| `ordersLastMonth` | number | Số đơn hàng tháng trước |
| `totalCustomers` | number | Tổng số khách hàng của store |

### `salesChart`

Dữ liệu doanh thu 6 tháng gần nhất từ materialized view `mv_monthly_revenue`. Trả về tối đa 6 bản ghi.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `month` | string | Tháng theo format `yyyy-MM` |
| `revenue` | number | Tổng doanh thu tháng đó |
| `orderCount` | number | Số đơn hàng tháng đó |

### `lowStockProducts`

Sản phẩm có tồn kho thấp hơn mức tối thiểu (`totalStock ≤ minStockLevel`), từ materialized view `mv_inventory_summary`. Trả về tối đa 10 sản phẩm.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `productName` | string | Tên sản phẩm |
| `sku` | string | Mã SKU |
| `totalStock` | number | Tổng tồn kho hiện tại |
| `minStockLevel` | number | Mức tồn kho tối thiểu |

### `recentOrders`

5 đơn hàng mới nhất của store.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `orderCode` | string | Mã đơn hàng |
| `customerName` | string | Tên khách hàng (hoặc "Khách vãng lai" nếu không có) |
| `totalAmount` | number | Tổng giá trị đơn hàng |
| `status` | string | Trạng thái đơn (`PENDING`, `PROCESSING`, `COMPLETED`, `CANCELLED`) |
| `createdAt` | string | Thời điểm tạo đơn (ISO 8601) |

---

## GET `/api/stores/{storeId}/dashboard`

Lấy toàn bộ dữ liệu dashboard của store. Yêu cầu user là **thành viên** của store.

Dữ liệu KPI và biểu đồ doanh thu được tổng hợp từ materialized view — được làm mới định kỳ theo lịch refresh của database, không phải real-time.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `storeId` | number | ID của store |

### Response `200 OK`

Trả về `DashboardResponse` như mô tả ở trên.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của store |
| 404 | `STORE_NOT_FOUND` | Store không tồn tại |
