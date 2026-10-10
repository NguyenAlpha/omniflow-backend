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

Lưu ý:
- Mỗi business chỉ có tối đa 1 invoice `PENDING` tại 1 thời điểm — backstop DB:
  unique index `ux_subscription_invoices_pending` (V9).
- Invoice `PENDING` quá hạn thanh toán (mặc định 7 ngày, cấu hình
  `subscription.invoice.pending-ttl-days`) bị scheduler tự động chuyển sang `FAILED`
  kèm `adminNote` — chặn kích hoạt plan bằng invoice giá cũ; owner tạo yêu cầu mới.

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
| `paymentAccountId` | number \| null | Tài khoản nhận tiền được chọn khi tạo hóa đơn |
| `bankInfo` | BankTransferInfoResponse \| null | Bản sao thông tin nhận tiền của hóa đơn; không đổi khi admin chuyển tài khoản |
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
| `qrImageUrl` | string \| null | Đường dẫn API ảnh QR lưu trên hóa đơn; cần Bearer token để đọc |

QR là ảnh admin tải lên cho tài khoản nhận tiền, được giữ nguyên theo hóa đơn.
Thay/gỡ ảnh tài khoản không làm đổi QR của hóa đơn cũ. GET đường dẫn
`/api/businesses/{businessId}/subscription/invoices/{invoiceId}/qr` trả PNG, yêu cầu
thành viên business (hoặc SUPER_ADMIN) và kiểm tra hóa đơn thuộc business đó.
Không có ảnh trả 404 `PAYMENT_QR_NOT_FOUND`. Bank-info không kèm invoiceId chỉ phục
vụ kiểm tra tài khoản hiện tại, trả `qrImageUrl: null`; dùng snapshot hóa đơn khi
thanh toán. Xem [PAYMENT_ACCOUNTS.md](PAYMENT_ACCOUNTS.md) về upload và lưu trữ.

---

## Đối tượng `UpgradeResponse`

Trả về sau khi tạo yêu cầu nâng cấp.

| Field | Type | Mô tả |
|:------|:-----|:------|
| `invoice` | `SubscriptionInvoiceResponse` | Invoice vừa tạo (status = `PENDING`) |
| `bankInfo` | `BankTransferInfoResponse` | Thông tin TK ngân hàng để chuyển khoản |

---

## Giá và giới hạn theo gói

Lưu trong bảng `subscription_plans`, **admin sửa được** qua
[`PUT /api/admin/plans/{code}`](#put-apiadminplanscode). Client đọc giá trị hiện hành
qua [`GET /api/plans`](#get-apiplans) — không hardcode. Giá trị khởi tạo (seed trong V2):

| Gói | MONTHLY | YEARLY | Stores | Staff | Products | Warehouses |
|:----|--------:|-------:|-------:|------:|---------:|-----------:|
| `FREE` | 0 VND | 0 VND | 1 | 0 | 50 | 1 |
| `BASIC` | 199.000 VND | 1.990.000 VND | 2 | 20 | 200 | 20 |
| `PRO` | 499.000 VND | 4.990.000 VND | 3 | Không giới hạn | Không giới hạn | Không giới hạn |

- **Giá** được chép vào `invoice.amount` lúc tạo invoice → đổi giá chỉ áp dụng cho invoice mới.
- **Giới hạn** được chép vào `subscriptions.max_*` khi đổi gói. Sub `ACTIVE` mang giới hạn của
  gói đang dùng; sub không `ACTIVE` (`EXPIRED`) mang giới hạn `FREE` dù `plan` giữ gói cũ.
  Admin sửa giới hạn → cập nhật ngay các bản sao này trong cùng transaction.
- Danh sách gói cố định `FREE` / `BASIC` / `PRO` — không tạo/xóa gói. Giá `FREE` luôn bằng 0.

---

## GET `/api/plans`

Giá và giới hạn hiện hành của các gói, theo thứ tự `FREE`, `BASIC`, `PRO`. **Công khai —
không cần JWT** (trang landing hiển thị cho khách chưa đăng nhập). Chịu rate limit theo IP
chung của `/api/**`.

### Response `200 OK`

```json
{
  "success": true,
  "data": [
    { "code": "FREE", "monthlyPrice": 0.00, "yearlyPrice": 0.00, "maxStores": 1, "maxStaff": 0, "maxProducts": 50, "maxWarehouses": 1 },
    { "code": "BASIC", "monthlyPrice": 199000.00, "yearlyPrice": 1990000.00, "maxStores": 2, "maxStaff": 20, "maxProducts": 200, "maxWarehouses": 20 },
    { "code": "PRO", "monthlyPrice": 499000.00, "yearlyPrice": 4990000.00, "maxStores": 3, "maxStaff": null, "maxProducts": null, "maxWarehouses": null }
  ],
  "error": null
}
```

`max*` = `null` nghĩa là không giới hạn.

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
| 403 | `FORBIDDEN` | Không phải OWNER |
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
| 403 | `FORBIDDEN` | Không phải OWNER |
| 404 | `SUBSCRIPTION_NOT_FOUND` | Business chưa có subscription |

---

## POST `/api/businesses/{businessId}/subscription/upgrade`

Tạo yêu cầu nâng cấp gói. Hệ thống tạo invoice PENDING và trả về thông tin TK ngân hàng để chuyển khoản.

**Quyền:** OWNER của business.

**Ràng buộc:**
- Sub `ACTIVE`: chỉ được nâng lên gói cao hơn gói hiện tại (FREE→BASIC, FREE→PRO, BASIC→PRO).
- Sub `EXPIRED`: được mua lại **bất kỳ gói trả phí nào** (renewal/re-subscribe) — kể cả gói
  bằng hoặc thấp hơn plan cũ (VD: PRO hết hạn mua lại PRO hoặc BASIC).
- `plan = FREE` luôn bị từ chối — không có gì để thanh toán.
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
      "amount": 199000,
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
| 400 | `VALIDATION_ERROR` | `plan = FREE` (không có gì để thanh toán) |
| 400 | `VALIDATION_ERROR` | Sub còn ACTIVE nhưng gói mới không cao hơn gói hiện tại |
| 400 | `VALIDATION_ERROR` | Đã có invoice PENDING chưa xử lý |
| 403 | `FORBIDDEN` | Không phải OWNER |
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
| 403 | `FORBIDDEN` | Không phải OWNER |
| 404 | `INVOICE_NOT_FOUND` | Invoice không tồn tại hoặc không thuộc business |

---

## GET `/api/businesses/{businessId}/subscription/bank-info`

Lấy thông tin nhận tiền từ database. Khi mở lại checkout, bắt buộc truyền query
`invoiceId` để lấy thông tin đã lưu trên hóa đơn đó. Không truyền `invoiceId` sẽ
trả tài khoản mặc định hiện tại, chỉ dùng cho xem trước/kiểm tra khả năng thanh toán.
Xem [PAYMENT_ACCOUNTS.md](./PAYMENT_ACCOUNTS.md) về chuyển dữ liệu và quản lý tài khoản.

**Quyền:** OWNER của business.

### Path parameters

| Parameter | Type | Mô tả |
|:----------|:-----|:-------|
| `businessId` | number | ID của business |

### Response `200 OK`

Trả về `BankTransferInfoResponse` hoặc `null` khi chưa có tài khoản mặc định/hóa đơn
không có dữ liệu lịch sử. Không dùng tài khoản hiện tại thay thế snapshot bị thiếu.

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 403 | `FORBIDDEN` | Không phải OWNER |

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
| 403 | `FORBIDDEN` | Không phải OWNER |
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
        "amount": 199000,
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
| 403 | `FORBIDDEN` | Không phải thành viên của business |

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
| 403 | `FORBIDDEN` | Không phải thành viên của business |
| 404 | `INVOICE_NOT_FOUND` | Invoice không tồn tại hoặc không thuộc business |

---

# Endpoints — Admin

> Tất cả endpoint admin đều yêu cầu role **`SUPER_ADMIN`**.

Base path: `/api/admin/subscriptions`

---

## GET `/api/admin/plans`

Danh sách gói cho trang quản trị — giống [`GET /api/plans`](#get-apiplans) và thêm:

| Field | Type | Mô tả |
|:------|:-----|:------|
| `version` | number | Gửi lại khi sửa (chống ghi đè khi 2 admin cùng sửa) |
| `updatedAt` | ISO 8601 | Lần sửa gần nhất |
| `affectedBusinesses` | number | Số business bị cập nhật giới hạn nếu sửa gói này: sub `ACTIVE` của gói; riêng `FREE` cộng thêm mọi sub không `ACTIVE` |

---

## PUT `/api/admin/plans/{code}`

Sửa giá và giới hạn của một gói (`code` = `FREE` / `BASIC` / `PRO`). Trong cùng transaction:
cập nhật `subscription_plans`, cập nhật `max_*` của mọi subscription bị ảnh hưởng (xem
`affectedBusinesses`) và ghi audit `ADMIN_PLAN_CONFIG_UPDATED` (giá trị trước/sau).

Hạ giới hạn không xóa dữ liệu (soft cap) — business đang vượt chỉ bị chặn tạo mới.

Dòng gói bị khóa `FOR UPDATE` khi sửa; các luồng chép giới hạn sang subscription (confirm
invoice, admin đổi gói, scheduler hết hạn/downgrade, tạo business) khóa `FOR SHARE` cùng dòng
→ không có subscription nào bị kích hoạt với giới hạn cũ trong lúc admin đang sửa.

### Request

```json
{
  "monthlyPrice": 249000,
  "yearlyPrice": 2490000,
  "maxStores": 2,
  "maxStaff": 25,
  "maxProducts": 300,
  "maxWarehouses": 20,
  "version": 0,
  "reason": "Điều chỉnh giá quý 4"
}
```

| Field | Type | Bắt buộc | Ràng buộc |
|:------|:-----|:--------:|:----------|
| `monthlyPrice`, `yearlyPrice` | number | ✅ | `>= 0`, tối đa 2 chữ số thập phân. Gói `FREE` phải bằng 0 |
| `maxStores`, `maxStaff`, `maxProducts`, `maxWarehouses` | number \| null | ❌ | `>= 0`; `null` = không giới hạn |
| `version` | number | ✅ | `version` đọc từ `GET /api/admin/plans` |
| `reason` | string | ❌ | Tối đa 500 ký tự, lưu vào audit |

### Response `200 OK`

Trả về gói sau khi sửa (cùng dạng phần tử của `GET /api/admin/plans`).

### Lỗi

| HTTP | `error.code` | Nguyên nhân |
|:----:|:------------|:-----------|
| 400 | `VALIDATION_ERROR` | Field sai ràng buộc, `code` không hợp lệ, hoặc giá gói `FREE` khác 0 |
| 409 | `CONCURRENT_MODIFICATION` | `version` đã cũ — gói vừa được admin khác sửa, tải lại rồi thử lại |

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

**Hành vi:**
- `plan = FREE`: `expiresAt = null`, `billingCycle = null` (FREE không có thời hạn).
- Plan trả phí: bắt buộc gửi `billingCycle` — `expiresAt` được tính lại = now + 30/365 ngày.
- Lịch downgrade đã đặt trước (`pendingPlan`/`pendingBillingCycle`) luôn bị xóa —
  quyết định của admin thay thế lịch cũ.

### Request

```json
{
  "plan": "PRO",
  "billingCycle": "MONTHLY"
}
```

| Field | Type | Bắt buộc | Giá trị |
|:------|:-----|:--------:|:--------|
| `plan` | string | ✅ | `FREE`, `BASIC`, `PRO` |
| `billingCycle` | string | Khi plan trả phí | `MONTHLY`, `YEARLY` — bỏ trống nếu `plan = FREE` |

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
        "amount": 4990000,
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
1. Tính lại kỳ sử dụng **từ thời điểm confirm**: `periodStart = confirmedAt`,
   `periodEnd = confirmedAt + 30/365 ngày` (không dùng kỳ đã chốt lúc owner tạo request —
   admin confirm trễ N ngày thì user không bị mất N ngày sử dụng)
2. Cập nhật invoice: `status = PAID`, ghi `paidAt`, `confirmedAt`, `confirmedBy`, `adminNote`
3. Cập nhật subscription: chuyển sang plan mới, `status = ACTIVE`, `expiresAt = periodEnd` mới

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
