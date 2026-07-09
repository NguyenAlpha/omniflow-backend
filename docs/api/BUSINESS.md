# API Reference — Business

Quản lý doanh nghiệp (business). Tất cả endpoint đều **yêu cầu JWT** — gửi kèm header `Authorization: Bearer <token>`.

---

## Response envelope

Xem [AUTH.md](./AUTH.md#response-envelope) để biết cấu trúc bọc ngoài chung.

---

## Đối tượng `BusinessResponse`

Dùng chung cho mọi endpoint trả về thông tin business.

```json
{
  "id": 1,
  "name": "Coffee Chain",
  "address": "123 Nguyễn Huệ, Q1, TP.HCM",
  "phone": "0901234567",
  "email": "contact@coffeechain.vn",
  "isActive": true,
  "createdAt": "2024-01-15T08:00:00Z",
  "updatedAt": "2024-01-15T08:00:00Z"
}
```

---

## Đối tượng `SubscriptionResponse`

Dùng cho endpoint trả về thông tin gói đăng ký.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `id` | number | Internal ID |
| `businessId` | number | ID của business |
| `plan` | string | Gói hiện tại: `FREE`, `BASIC`, `PRO` |
| `status` | string | Trạng thái: `ACTIVE`, `EXPIRED`, `CANCELLED` |
| `billingCycle` | string \| null | Chu kỳ thanh toán: `MONTHLY`, `YEARLY` — null nếu là gói FREE |
| `maxStores` | number \| null | Giới hạn số cửa hàng — null nghĩa là không giới hạn |
| `maxStaff` | number \| null | Giới hạn số nhân viên |
| `maxProducts` | number \| null | Giới hạn số sản phẩm |
| `maxWarehouses` | number \| null | Giới hạn số kho |
| `startedAt` | ISO 8601 | Ngày bắt đầu gói |
| `expiresAt` | ISO 8601 \| null | Ngày hết hạn — null nếu không có thời hạn |
| `createdAt` | ISO 8601 | Thời điểm tạo |
| `updatedAt` | ISO 8601 | Thời điểm cập nhật gần nhất |
| `pendingPlan` | string \| null | Gói sẽ áp dụng cuối chu kỳ nếu đã đặt lịch downgrade |
| `pendingBillingCycle` | string \| null | Dự trữ — hiện luôn null |

---

## POST `/api/businesses/default`

Tạo business mặc định kèm store và warehouse đầu tiên — dùng trong luồng onboarding khi user đăng ký mới. User gọi endpoint này sẽ được gán role **OWNER** của business vừa tạo.

> Không nhận body.

**Idempotent:** nếu user đã là OWNER của ít nhất 1 business (VD: client retry do mất mạng,
double-tap), endpoint trả về business hiện có cùng store/warehouse đầu tiên của nó
(có thể `null` nếu business chưa có store/warehouse) thay vì tạo bộ mới trùng lặp.

### Response `201 Created`

```json
{
  "success": true,
  "data": {
    "business": {
      "id": 1,
      "name": "Doanh nghiệp của tôi",
      "address": null,
      "phone": null,
      "email": null,
      "isActive": true,
      "createdAt": "2024-01-15T08:00:00Z",
      "updatedAt": "2024-01-15T08:00:00Z"
    },
    "store": {
      "id": 1,
      "name": "Cửa hàng số 1",
      "address": "",
      "phone": "",
      "email": "",
      "isActive": true,
      "createdAt": "2024-01-15T08:00:00Z",
      "updatedAt": "2024-01-15T08:00:00Z"
    },
    "warehouse": {
      "id": 1,
      "publicId": "a1b2c3d4-...",
      "storeId": 1,
      "name": "Kho số 1",
      "address": null,
      "isActive": true,
      "syncVersion": 0,
      "lastModifiedAt": "2024-01-15T08:00:00Z",
      "createdAt": "2024-01-15T08:00:00Z",
      "updatedAt": "2024-01-15T08:00:00Z"
    }
  },
  "error": null
}
```

---

## POST `/api/businesses`

Tạo business mới. User gọi endpoint này sẽ được gán role **OWNER** của business vừa tạo.

### Request

```json
{
  "name": "Coffee Chain",
  "address": "123 Nguyễn Huệ, Q1, TP.HCM",
  "phone": "0901234567",
  "email": "contact@coffeechain.vn"
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
    "id": 2,
    "name": "Coffee Chain",
    "address": "123 Nguyễn Huệ, Q1, TP.HCM",
    "phone": "0901234567",
    "email": "contact@coffeechain.vn",
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
| 400 | `VALIDATION_ERROR` | Field không hợp lệ — `error.field` chỉ rõ field nào |

---

## GET `/api/businesses`

Lấy danh sách tất cả business mà user hiện tại là thành viên.

> Không nhận body hoặc query param. SUPER_ADMIN nhận toàn bộ danh sách.

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "Coffee Chain",
      "address": "123 Nguyễn Huệ, Q1, TP.HCM",
      "phone": "0901234567",
      "email": "contact@coffeechain.vn",
      "isActive": true,
      "createdAt": "2024-01-15T08:00:00Z",
      "updatedAt": "2024-01-15T08:00:00Z"
    }
  ],
  "error": null
}
```

> Trả về mảng rỗng `[]` nếu user chưa thuộc business nào.

---

## GET `/api/businesses/{businessId}`

Lấy thông tin chi tiết một business.

**Quyền:** Thành viên của business (`isMember` — OWNER, MANAGER, hoặc STAFF của bất kỳ store trong business).

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "Coffee Chain",
    "address": "123 Nguyễn Huệ, Q1, TP.HCM",
    "phone": "0901234567",
    "email": "contact@coffeechain.vn",
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
| 403 | `ACCESS_DENIED` | Không phải thành viên của business |
| 404 | `BUSINESS_NOT_FOUND` | Business không tồn tại |

---

## GET `/api/businesses/{businessId}/subscription`

Lấy thông tin gói đăng ký hiện tại của business.

**Quyền:** Thành viên của business (`isMember`).

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Response `200 OK`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "businessId": 1,
    "plan": "FREE",
    "status": "ACTIVE",
    "billingCycle": null,
    "maxStores": 1,
    "maxStaff": 0,
    "maxProducts": 50,
    "maxWarehouses": 1,
    "startedAt": "2024-01-15T08:00:00Z",
    "expiresAt": null,
    "createdAt": "2024-01-15T08:00:00Z",
    "updatedAt": "2024-01-15T08:00:00Z"
  },
  "error": null
}
```

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `ACCESS_DENIED` | Không phải thành viên của business |
| 404 | `BUSINESS_NOT_FOUND` | Business không tồn tại |
| 404 | `SUBSCRIPTION_NOT_FOUND` | Business chưa có subscription (không xảy ra bình thường) |

---

## POST `/api/businesses/{businessId}/subscription/upgrade`

Tạo yêu cầu nâng cấp gói. Xem tài liệu đầy đủ tại **[SUBSCRIPTION.md](./SUBSCRIPTION.md#post-apibusinessesbusinessidsubscriptionupgrade)**.

**Quyền:** OWNER — `201 Created` → `UpgradeResponse` (invoice PENDING + thông tin TK ngân hàng)

---

## POST `/api/businesses/{businessId}/subscription/downgrade`

Đặt lịch hạ cấp gói, có hiệu lực cuối chu kỳ hiện tại. Xem **[SUBSCRIPTION.md](./SUBSCRIPTION.md#post-apibusinessesbusinessidsubscriptiondowngrade)**.

**Quyền:** OWNER — `200 OK` → `SubscriptionResponse`

---

## DELETE `/api/businesses/{businessId}/subscription/downgrade`

Hủy lịch hạ cấp đã đặt. Xem **[SUBSCRIPTION.md](./SUBSCRIPTION.md#delete-apibusinessesbusinessidsubscriptiondowngrade)**.

**Quyền:** OWNER — `200 OK` → `SubscriptionResponse`

---

## DELETE `/api/businesses/{businessId}/subscription/invoices/{invoiceId}`

Hủy invoice PENDING. Xem **[SUBSCRIPTION.md](./SUBSCRIPTION.md#delete-apibusinessesbusinessidsubscriptioninvoicesinvoiceid)**.

**Quyền:** OWNER — `204 No Content`

---

## GET `/api/businesses/{businessId}/subscription/bank-info`

Lấy thông tin tài khoản ngân hàng để hiển thị lại màn hình checkout. Xem **[SUBSCRIPTION.md](./SUBSCRIPTION.md#get-apibusinessesbusinessidsubscriptionbank-info)**.

**Quyền:** OWNER — `200 OK` → `BankTransferInfoResponse`

---

## PATCH `/api/businesses/{businessId}/subscription/invoices/{invoiceId}/payment`

Gửi nội dung/mã chuyển khoản để admin đối chiếu. Xem **[SUBSCRIPTION.md](./SUBSCRIPTION.md#patch-apibusinessesbusinessidsubscriptioninvoicesinvoiceidpayment)**.

**Quyền:** OWNER — `200 OK` → `SubscriptionInvoiceResponse`

---

## GET `/api/businesses/{businessId}/subscription/invoices`

Lịch sử invoice của business (phân trang). Xem **[SUBSCRIPTION.md](./SUBSCRIPTION.md#get-apibusinessesbusinessidsubscriptioninvoices)**.

**Quyền:** Thành viên (`isMember`) — `200 OK` → `Page<SubscriptionInvoiceResponse>`

---

## GET `/api/businesses/{businessId}/subscription/invoices/{invoiceId}`

Chi tiết một invoice. Xem **[SUBSCRIPTION.md](./SUBSCRIPTION.md#get-apibusinessesbusinessidsubscriptioninvoicesinvoiceid)**.

**Quyền:** Thành viên (`isMember`) — `200 OK` → `SubscriptionInvoiceResponse`

---

## PATCH `/api/businesses/{businessId}`

Cập nhật thông tin business. Chỉ **OWNER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Request

```json
{
  "name": "Coffee Chain (mới)",
  "address": "456 Lê Lợi, Q1, TP.HCM",
  "phone": "0907654321",
  "email": "new@coffeechain.vn"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `name` | string | ✅ | max 200 ký tự |
| `address` | string | ❌ | — |
| `phone` | string | ❌ | 8–20 ký tự, chỉ chứa `0-9`, `+`, `-`, `(`, `)`, space |
| `email` | string | ❌ | format email hợp lệ, max 100 ký tự |

### Response `200 OK`

Trả về `BusinessResponse` sau khi cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field không hợp lệ |
| 403 | `ACCESS_DENIED` | Không phải OWNER của business |
| 404 | `BUSINESS_NOT_FOUND` | Business không tồn tại |

---

## PATCH `/api/businesses/{businessId}/status`

Kích hoạt hoặc vô hiệu hoá business. Chỉ **OWNER** mới được thực hiện.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

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

Trả về `BusinessResponse` với `isActive` đã được cập nhật.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | `isActive` bị bỏ trống |
| 403 | `ACCESS_DENIED` | Không phải OWNER của business |
| 404 | `BUSINESS_NOT_FOUND` | Business không tồn tại |
