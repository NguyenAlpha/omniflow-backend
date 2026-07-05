# API Reference — Subscription

Quản lý gói đăng ký dịch vụ. Tất cả endpoint đều **yêu cầu JWT**.

---

## Luồng thanh toán chuyển khoản thủ công

```
Business Owner                     Admin
      │                              │
      │  POST /upgrade               │
      │──────────────────────────────>  tạo Invoice PENDING
      │<── UpgradeResponse                bankTransferRef tự động (VD: "basic 5")
      │                                   + thông tin TK ngân hàng + QR
      │
      │  (Owner chuyển khoản với nội dung "basic 5")
      │
      │  (tuỳ chọn) DELETE .../invoices/{id}  → hủy Invoice PENDING
      │
      │                  GET /invoices/pending
      │                   <──────────│
      │                   ──────────>│  danh sách Invoice chờ duyệt
      │                              │
      │                  POST .../confirm
      │                   <──────────│  Admin xác nhận
      │                   ──────────>│
      │                              │  Invoice → PAID
      │                              │  Subscription → plan mới, ACTIVE
      │                              │  expiresAt = now + 30/365 ngày
```

**`bankTransferRef` được backend tự sinh** khi tạo invoice, theo format `{plan} {invoiceId}` (chữ thường), ví dụ `"basic 5"`, `"pro 12"`. Owner không cần nhập tay — chỉ cần copy và dùng làm nội dung chuyển khoản.

Trạng thái Invoice:

| Trạng thái | Ý nghĩa |
|:-----------|:--------|
| `PENDING` | Vừa tạo, đang chờ owner chuyển khoản và admin xác nhận |
| `PAID` | Admin đã xác nhận — subscription đã được nâng cấp |
| `FAILED` | Admin từ chối — subscription không thay đổi |

---

## Đối tượng `SubscriptionInvoiceResponse`

| Field | Type | Mô tả |
|:------|:-----|:------|
| `id` | number | Internal ID |
| `businessId` | number | ID của business |
| `plan` | string | Gói muốn nâng lên: `BASIC`, `PRO` |
| `billingCycle` | string | Chu kỳ: `MONTHLY`, `YEARLY` |
| `amount` | number | Số tiền cần chuyển khoản (VND) |
| `status` | string | `PENDING`, `PAID`, `FAILED` |
| `bankTransferRef` | string \| null | Nội dung CK owner đã gửi lên |
| `adminNote` | string \| null | Ghi chú của admin khi confirm/reject |
| `periodStart` | ISO 8601 | Ngày bắt đầu kỳ subscription |
| `periodEnd` | ISO 8601 | Ngày kết thúc kỳ subscription |
| `paidAt` | ISO 8601 \| null | Thời điểm xác nhận PAID |
| `confirmedAt` | ISO 8601 \| null | Thời điểm admin confirm |
| `createdAt` | ISO 8601 | Thời điểm tạo invoice |
| `updatedAt` | ISO 8601 | Thời điểm cập nhật gần nhất |

---

## Đối tượng `BankTransferInfoResponse`

Thông tin tài khoản ngân hàng để business owner chuyển tiền.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `bankName` | string | Tên ngân hàng |
| `accountNumber` | string | Số tài khoản |
| `accountHolder` | string | Tên chủ tài khoản |
| `branch` | string | Chi nhánh |

---

## Đối tượng `UpgradeResponse`

Trả về sau khi tạo yêu cầu nâng cấp.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `invoice` | `SubscriptionInvoiceResponse` | Invoice vừa tạo (status = `PENDING`) |
| `bankInfo` | `BankTransferInfoResponse` | Thông tin TK ngân hàng để chuyển khoản |

---

## Bảng giá

| Gói | MONTHLY | YEARLY |
|:----|--------:|-------:|
| `BASIC` | 199.000 VND | 1.990.000 VND |
| `PRO` | 499.000 VND | 4.990.000 VND |
| `FREE` | 0 VND | 0 VND |

---

## Giới hạn theo gói

| Gói | Stores | Staff | Products | Warehouses |
|:----|-------:|------:|---------:|-----------:|
| `FREE` | 1 | 0 | 50 | 1 |
| `BASIC` | 2 | 20 | 200 | 20 |
| `PRO` | 3 | Không giới hạn | Không giới hạn | Không giới hạn |

---

# Endpoints — Business Owner

> Các endpoint dưới đây thuộc nhóm `/api/businesses/{businessId}/subscription/...`
> Xem thêm: [BUSINESS.md](./BUSINESS.md) cho endpoint `GET /{businessId}/subscription`.

---

## POST `/api/businesses/{businessId}/subscription/downgrade`

Đặt lịch hạ cấp gói, hiệu lực vào cuối chu kỳ hiện tại (`expiresAt`). Không hoàn tiền. Dữ liệu hiện có không bị xóa (soft cap — chỉ chặn tạo mới khi vượt giới hạn gói mới).

**Quyền:** OWNER của business.

**Ràng buộc:**
- Subscription phải đang `ACTIVE`.
- Không thể downgrade nếu đang ở gói `FREE`.
- Gói mới phải thấp hơn gói hiện tại (PRO→BASIC, PRO→FREE, BASIC→FREE).
- Downgrade về `FREE` = cancel tại cuối chu kỳ (subscription chuyển sang ACTIVE vô thời hạn).
- Downgrade về gói paid thấp hơn: subscription chuyển sang EXPIRED tại cuối chu kỳ, user cần re-subscribe.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Request

```json
{
  "plan": "FREE"
}
```

| Field | Type | Bắt buộc | Giá trị |
|:------|:-----|:--------:|:--------|
| `plan` | string | ✅ | `FREE`, `BASIC`, `PRO` (phải thấp hơn plan hiện tại) |

### Response `200 OK`

Trả về `SubscriptionResponse` với `pendingPlan` đã được set.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Subscription không ACTIVE |
| 400 | `VALIDATION_ERROR` | Đang ở gói FREE, không thể downgrade tiếp |
| 400 | `VALIDATION_ERROR` | Gói mới không thấp hơn gói hiện tại |
| 403 | `ACCESS_DENIED` | Không phải OWNER |
| 404 | `SUBSCRIPTION_NOT_FOUND` | Business chưa có subscription |

---

## DELETE `/api/businesses/{businessId}/subscription/downgrade`

Huỷ lịch downgrade đã đặt trước — subscription tiếp tục ở gói hiện tại khi hết chu kỳ (sẽ EXPIRED thay vì downgrade).

**Quyền:** OWNER của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Response `200 OK`

Trả về `SubscriptionResponse` với `pendingPlan = null`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Không có lịch downgrade nào để huỷ |
| 403 | `ACCESS_DENIED` | Không phải OWNER |
| 404 | `SUBSCRIPTION_NOT_FOUND` | Business chưa có subscription |

---

## POST `/api/businesses/{businessId}/subscription/upgrade`

Tạo yêu cầu nâng cấp gói. Hệ thống tạo invoice PENDING và trả về thông tin TK ngân hàng để chuyển khoản.

**Quyền:** OWNER của business.

**Ràng buộc:**
- Chỉ được nâng lên gói cao hơn gói hiện tại (FREE→BASIC, FREE→PRO, BASIC→PRO).
- Nếu đã có invoice PENDING chưa xử lý → lỗi 400.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Request

```json
{
  "plan": "BASIC",
  "billingCycle": "MONTHLY"
}
```

| Field | Type | Bắt buộc | Giá trị |
|:------|:-----|:--------:|:--------|
| `plan` | string | ✅ | `BASIC`, `PRO` |
| `billingCycle` | string | ✅ | `MONTHLY`, `YEARLY` |

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "invoice": {
      "id": 1,
      "businessId": 1,
      "plan": "BASIC",
      "billingCycle": "MONTHLY",
      "amount": 299000,
      "status": "PENDING",
      "bankTransferRef": "basic 1",
      "adminNote": null,
      "periodStart": "2026-06-19T08:00:00Z",
      "periodEnd": "2026-07-19T08:00:00Z",
      "paidAt": null,
      "confirmedAt": null,
      "createdAt": "2026-06-19T08:00:00Z",
      "updatedAt": "2026-06-19T08:00:00Z"
    },
    "bankInfo": {
      "bankName": "Vietcombank",
      "accountNumber": "1234567890",
      "accountHolder": "CONG TY QUIKTECH",
      "branch": "Chi nhánh TP.HCM"
    }
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `plan` hoặc `billingCycle` không hợp lệ |
| 400 | `VALIDATION_ERROR` | Gói mới không cao hơn gói hiện tại |
| 400 | `VALIDATION_ERROR` | Đã có invoice PENDING chưa xử lý |
| 403 | `ACCESS_DENIED` | Không phải OWNER |
| 404 | `SUBSCRIPTION_NOT_FOUND` | Business chưa có subscription |

---

## DELETE `/api/businesses/{businessId}/subscription/invoices/{invoiceId}`

Business owner huỷ invoice PENDING. Chỉ được phép khi invoice chưa được admin xử lý.

**Quyền:** OWNER của business.

**Ràng buộc:**
- Invoice phải ở trạng thái `PENDING`.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `invoiceId` | number | ID của invoice |

### Response `204 No Content`

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Invoice không ở trạng thái PENDING |
| 403 | `ACCESS_DENIED` | Không phải OWNER |
| 404 | `INVOICE_NOT_FOUND` | Invoice không tồn tại hoặc không thuộc business |

---

## GET `/api/businesses/{businessId}/subscription/bank-info`

Lấy thông tin tài khoản ngân hàng để hiển thị lại màn hình checkout (dùng khi user đóng modal rồi mở lại từ pending banner).

**Quyền:** OWNER của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Response `200 OK`

Trả về `BankTransferInfoResponse`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải OWNER |

---

## PATCH `/api/businesses/{businessId}/subscription/invoices/{invoiceId}/payment`

> **Lưu ý:** Endpoint này không còn được dùng trong luồng chính của frontend. `bankTransferRef` hiện được backend tự sinh khi tạo invoice (`POST /upgrade`). Endpoint vẫn tồn tại nhưng không cần thiết trong quy trình thông thường.

Business owner gửi nội dung/mã chuyển khoản để admin có thể đối chiếu với giao dịch ngân hàng.

**Quyền:** OWNER của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `invoiceId` | number | ID của invoice |

### Request

```json
{
  "bankTransferRef": "QUIKTECH 1 THANH TOAN GOI BASIC"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `bankTransferRef` | string | ✅ | max 100 ký tự, không rỗng |

### Response `200 OK`

Trả về `SubscriptionInvoiceResponse` với `bankTransferRef` đã được lưu.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `bankTransferRef` rỗng hoặc vượt 100 ký tự |
| 400 | `VALIDATION_ERROR` | Invoice đã PAID hoặc FAILED |
| 403 | `ACCESS_DENIED` | Không phải OWNER |
| 404 | `INVOICE_NOT_FOUND` | Invoice không tồn tại hoặc không thuộc business |

---

## GET `/api/businesses/{businessId}/subscription/invoices`

Xem lịch sử invoice của business (phân trang, mới nhất trước).

**Quyền:** Thành viên của business (`isMember`).

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Query parameters

| Parameter | Mặc định | Mô tả |
|:----------|:--------:|:-------|
| `page` | 0 | Trang (0-indexed) |
| `size` | 20 | Số phần tử mỗi trang |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 1,
        "businessId": 1,
        "plan": "BASIC",
        "billingCycle": "MONTHLY",
        "amount": 299000,
        "status": "PAID",
        "bankTransferRef": "QUIKTECH 1 THANH TOAN GOI BASIC",
        "adminNote": "Đã đối chiếu giao dịch ngân hàng",
        "periodStart": "2026-06-19T08:00:00Z",
        "periodEnd": "2026-07-19T08:00:00Z",
        "paidAt": "2026-06-20T10:00:00Z",
        "confirmedAt": "2026-06-20T10:00:00Z",
        "createdAt": "2026-06-19T08:00:00Z",
        "updatedAt": "2026-06-20T10:00:00Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "size": 20,
    "number": 0
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của business |

---

## GET `/api/businesses/{businessId}/subscription/invoices/{invoiceId}`

Xem chi tiết một invoice cụ thể.

**Quyền:** Thành viên của business (`isMember`).

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |
| `invoiceId` | number | ID của invoice |

### Response `200 OK`

Trả về `SubscriptionInvoiceResponse`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của business |
| 404 | `INVOICE_NOT_FOUND` | Invoice không tồn tại hoặc không thuộc business |

---

# Endpoints — Admin

> Tất cả endpoint admin đều yêu cầu role **`SUPER_ADMIN`**.

Base path: `/api/admin/subscriptions`

---

## GET `/api/admin/subscriptions/{businessId}`

Xem thông tin subscription hiện tại của một business bất kỳ.

### Response `200 OK`

Trả về `SubscriptionResponse` (xem [BUSINESS.md](./BUSINESS.md#đối-tượng-subscriptionresponse)).

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 404 | `SUBSCRIPTION_NOT_FOUND` | Business chưa có subscription |

---

## PATCH `/api/admin/subscriptions/{businessId}/plan`

Override trực tiếp plan của business — không qua luồng invoice. Dùng cho điều chỉnh thủ công (tặng gói, sửa lỗi).

### Request

```json
{
  "plan": "PRO"
}
```

| Field | Type | Bắt buộc | Giá trị |
|:------|:-----|:--------:|:--------|
| `plan` | string | ✅ | `FREE`, `BASIC`, `PRO` |

### Response `200 OK`

Trả về `SubscriptionResponse` sau khi cập nhật.

---

## GET `/api/admin/subscriptions/invoices/pending`

Danh sách tất cả invoice đang ở trạng thái `PENDING` của toàn hệ thống (phân trang, mới nhất trước). Dùng để admin biết invoice nào cần xem xét.

### Query parameters

| Parameter | Mặc định | Mô tả |
|:----------|:--------:|:-------|
| `page` | 0 | Trang (0-indexed) |
| `size` | 20 | Số phần tử mỗi trang |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 3,
        "businessId": 2,
        "plan": "PRO",
        "billingCycle": "YEARLY",
        "amount": 6990000,
        "status": "PENDING",
        "bankTransferRef": "CK NANG CAP GOI PRO 12 THANG",
        "adminNote": null,
        "periodStart": "2026-06-19T08:00:00Z",
        "periodEnd": "2027-06-19T08:00:00Z",
        "paidAt": null,
        "confirmedAt": null,
        "createdAt": "2026-06-19T08:00:00Z",
        "updatedAt": "2026-06-19T09:00:00Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "size": 20,
    "number": 0
  },
  "error": null
}
```

---

## POST `/api/admin/subscriptions/invoices/{invoiceId}/confirm`

Admin xác nhận thanh toán chuyển khoản thành công. Hệ thống:
1. Cập nhật invoice: `status = PAID`, ghi `paidAt`, `confirmedAt`, `confirmedBy`, `adminNote`
2. Cập nhật subscription: chuyển sang plan mới, `status = ACTIVE`, set `expiresAt`

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `invoiceId` | number | ID của invoice |

### Request

```json
{
  "adminNote": "Đã đối chiếu giao dịch VCB ngày 19/06/2026, mã GD 123456"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `adminNote` | string | ❌ | max 500 ký tự |

### Response `200 OK`

Trả về `SubscriptionInvoiceResponse` với `status = PAID`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Invoice đã PAID hoặc đã FAILED |
| 404 | `INVOICE_NOT_FOUND` | Invoice không tồn tại |

---

## POST `/api/admin/subscriptions/invoices/{invoiceId}/reject`

Admin từ chối thanh toán. Invoice chuyển sang `FAILED`, subscription không thay đổi.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `invoiceId` | number | ID của invoice |

### Request

```json
{
  "adminNote": "Số tiền chuyển không đúng (thiếu 1.000 VND). Vui lòng liên hệ hỗ trợ."
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `adminNote` | string | ✅ | max 500 ký tự, không rỗng |

### Response `200 OK`

Trả về `SubscriptionInvoiceResponse` với `status = FAILED`.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `adminNote` rỗng |
| 400 | `VALIDATION_ERROR` | Invoice đã PAID hoặc đã FAILED |
| 404 | `INVOICE_NOT_FOUND` | Invoice không tồn tại |

---

## GET `/api/admin/subscriptions/stats`

Trả về thống kê tổng hợp toàn hệ thống. Yêu cầu role **SUPER_ADMIN**.

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "totalBusinesses": 42,
    "totalUsers": 128,
    "activeUsers": 120,
    "pendingInvoices": 3,
    "freePlan": 30,
    "basicPlan": 8,
    "proPlan": 4,
    "activeSubscriptions": 35,
    "expiredSubscriptions": 6,
    "revenueThisMonth": 5970000,
    "revenueLast6Months": [
      { "month": "2025-07", "amount": 1990000 },
      { "month": "2025-08", "amount": 3980000 }
    ]
  }
}
```

**Nguồn dữ liệu:**
- `businesses` table → `totalBusinesses`
- `users` table (non-deleted) → `totalUsers`, `activeUsers`
- `subscription_invoices` WHERE `status = PENDING` → `pendingInvoices`
- `subscriptions` grouped by `plan` → `freePlan`, `basicPlan`, `proPlan`
- `subscriptions` grouped by `status` → `activeSubscriptions`, `expiredSubscriptions`
- `subscription_invoices` WHERE `status = PAID` → doanh thu tháng hiện tại + 6 tháng gần nhất
