# Thao tác quản trị (Admin operations)

Mọi endpoint `/api/admin/**` đều yêu cầu JWT hợp lệ và quyền `SUPER_ADMIN`. Response
dùng envelope `{ success, data, error }` hiện có.

Phần quản trị tài khoản nhận tiền và snapshot trên hóa đơn được mô tả trong
[PAYMENT_ACCOUNTS.md](./PAYMENT_ACCOUNTS.md). Các thao tác thay đổi tài khoản nhận tiền
cũng xuất hiện trong endpoint audit của quản trị viên.

## GET `/api/admin/session`

Trả về `UserSummaryResponse` của người dùng hiện tại sau khi kiểm tra quyền quản trị.
Web client gọi endpoint này trước khi lưu phiên đăng nhập admin và khi mở trang quản trị.
Người dùng thường nhận 403; token thiếu/không hợp lệ nhận 401. Server vẫn là nơi quyết
định cuối cùng cho mọi thao tác được bảo vệ.

## GET `/api/admin/businesses/{businessId}`

Trả về `{ business, subscription, stores }` dùng các response DTO hiện có. Danh sách store
chỉ thuộc business được yêu cầu và loại bỏ các bản ghi đã xóa mềm. Business không tồn tại
trả 404; người không phải quản trị viên nhận 403. Lịch sử hóa đơn vẫn nằm ở
`GET /api/businesses/{businessId}/subscription/invoices?page=0&size=20`.

## Lịch sử hóa đơn và số hóa đơn chờ xử lý

`GET /api/admin/subscriptions/invoices` nhận các tham số tùy chọn `status` (PENDING, PAID,
FAILED), `businessId` (số dương), `q` (tối đa 100 ký tự) và phân trang `page` / `size`.
Tìm kiếm khớp tên business/mã tham chiếu chuyển khoản không phân biệt hoa thường, hoặc
khớp chính xác ID hóa đơn/business dạng số. `%` và `_` được xem là ký tự thường khi tìm.
Kết quả dùng invoice DTO hiện có trong envelope phân trang `PagedResult`
(`content, page, size, totalElements, totalPages`), sắp xếp theo `createdAt DESC, id DESC`;
kích thước trang tối đa 100. Tham số sai kiểu trả 400 với `VALIDATION_ERROR`.

`GET /api/admin/subscriptions/invoices/pending/count` trả về số hóa đơn đang chờ xử lý
hiện tại. Cả hai endpoint đều yêu cầu SUPER_ADMIN.

## Audit quản trị viên

`GET /api/admin/audit-logs?size=20` trả về `{ content, nextCursor }`. Bộ lọc tùy chọn:
`businessId`, `actorId`, `action` và `beforeId` (giá trị `nextCursor` của response trước).
ID phải là số dương; `size` từ 1–100. Kết quả phân trang keyset theo ID giảm dần. Chỉ
SUPER_ADMIN được đọc endpoint này.

Các action được ghi lại: `ADMIN_PLAN_CHANGED`, `ADMIN_INVOICE_CONFIRMED`,
`ADMIN_INVOICE_REJECTED`, `ADMIN_USER_STATUS_CHANGED`, `ADMIN_USER_DELETED`,
`ADMIN_PLAN_CONFIG_UPDATED` (sửa giá/giới hạn gói) và các action tài khoản nhận tiền
`ADMIN_PAYMENT_ACCOUNT_CREATED`, `ADMIN_PAYMENT_ACCOUNT_UPDATED`,
`ADMIN_PAYMENT_ACCOUNT_ACTIVATED`, `ADMIN_PAYMENT_ACCOUNT_ARCHIVED`.
Mỗi bản ghi gồm ID/tên người thực hiện tại thời điểm đó, thời gian, entity, business, lý do
và snapshot trước/sau. Xác nhận hóa đơn ghi cả thay đổi của hóa đơn lẫn subscription.
Snapshot user chỉ chứa ID, username, trạng thái active và thời điểm xóa. Mật khẩu, token và
toàn bộ entity user không bao giờ được serialize.

Request đổi gói/đổi trạng thái nhận `reason` tùy chọn (tối đa 500 ký tự). DELETE user nhận
body JSON tùy chọn `{ "reason": "..." }`; các request không có body như trước vẫn được hỗ
trợ. Lý do cho hóa đơn dùng trường `adminNote` hiện có.

Service ghi đồng bộ vào bảng `audit_logs` hiện có, trong cùng transaction với thao tác thay
đổi (propagation `MANDATORY`). Ghi audit thất bại sẽ rollback thao tác. Không cần migration
schema hay backfill dữ liệu cũ. Audit logger bất đồng bộ dùng chung vẫn tách riêng. Audit chỉ
bắt đầu ghi sau khi phiên bản này được deploy, và chỉ bao gồm mười action liệt kê ở trên, không
phải mọi thao tác của hệ thống/merchant. Các thao tác trên hóa đơn, override subscription và
đổi trạng thái/xóa tài khoản đều khóa dòng dữ liệu đích để tuần tự hóa các thay đổi đồng thời
của quản trị viên.

Chạy `node scripts/check-admin-audit-rollback.mjs` với thông tin đăng nhập admin trong biến
môi trường để kiểm tra một lần insert PostgreSQL thất bại thật và rollback transaction.
Script này cố định dùng port riêng 8081/database `quiktech_admin_checks` và container local
`quiktech-pos-db`. Nó tạo một check constraint tạm nhắm vào một `reason` duy nhất, gỡ bỏ
constraint trong `finally`, và xóa mềm user giả lập mà nó tạo ra.

Đã kiểm chứng ngày 2026-10-06 trên PostgreSQL: snapshot người thực hiện/lý do/trước-sau,
người dùng thường nhận 403, bộ lọc sai/lý do quá dài nhận 400, biên của cursor, không ghi
audit cho thao tác thất bại, xác nhận đồng thời chỉ được áp dụng đúng một lần, và
subscription được rollback hoàn toàn khi ép insert audit thất bại. Constraint tạm đã được gỡ
sau khi kiểm chứng. Không có dữ liệu business thật nào bị thay đổi.

## Dashboard lưu lượng API

Cả hai endpoint đều yêu cầu `SUPER_ADMIN`.

### Cách ghi nhận lưu lượng

`ApiTrafficFilter` (servlet filter ngoài cùng, chạy trước rate limiting và Spring Security)
đo mọi request `/api/**` trừ CORS preflight, nên các response 401/429 cũng được đếm. Filter
ghi **route pattern** của Spring (ví dụ `/api/stores/{storeId}/orders`) thay vì URL thô;
request bị từ chối trước khi tới controller được lưu là `(unmatched)`. Business được lấy từ
path variable `businessId`/`storeId` (store được quy về business của nó); các route
`/api/admin/**` không được gán cho business nào.

`ApiTrafficRecorder` gộp số liệu trong bộ nhớ và mỗi phút UPSERT các phút đã kết thúc vào
`api_traffic_minutely` (giữ 2 ngày), `api_traffic_hourly` và `api_traffic_business_hourly`
(giữ 30 ngày) — xem V11. Upsert cộng dồn vào dòng có sẵn, nên nhiều instance có thể cùng
ghi một bucket. Ghi thất bại thì chỉ log và bỏ qua, không bao giờ ảnh hưởng tới request.
Phút hiện tại chưa được lưu, nên dashboard trễ khoảng 1–2 phút. Thời gian phản hồi được đếm
theo bucket (≤50, ≤100, ≤250, ≤500, ≤1000, ≤2500, ≤5000, >5000 ms); p50/p95/p99 là cận trên
của bucket chứa percentile đó, và là `null` nếu vượt 5000 ms.

### GET `/api/admin/traffic?range=1h|24h|7d|30d`

Mặc định `24h`. `1h`/`24h` đọc bảng theo phút (mỗi điểm 1 / 15 phút); `7d`/`30d` đọc bảng
theo giờ (mỗi điểm 2 / 6 giờ). Trả về `summary` (số request, trung bình mỗi phút, 5xx, 4xx,
429, avg/p50/p95/p99/max ms), `series` (các điểm đã điền 0 cho khoảng trống, gồm request,
4xx, 5xx, avg và p95), `statuses` (số lượng theo từng HTTP status), `endpoints` (top 50 theo
số request, kèm 4xx/5xx, avg, p95, max) và `businesses` (top 20 theo số request; với `1h`
và `24h` khoảng thời gian bắt đầu từ đầu giờ). `range` không hợp lệ trả 400
`VALIDATION_ERROR`.

### GET `/api/admin/traffic/system`

Giá trị tức thời của instance đã trả lời request: tình trạng health tổng thể và trạng thái
từng thành phần (`db`, `redis`, …), uptime, heap đã dùng/tối đa, CPU của process và của máy
(0–1), số thread đang chạy và pool Hikari active/idle/max/pending. Các trường không đo được
là `null`. Không lưu lịch sử; khi có nhiều instance, mỗi lần gọi có thể vào một instance
khác nhau.

## Kiểm chứng thực tế (2026-10-06)

Repository web có script `scripts/check-admin-live.mjs`. Đặt `ADMIN_USERNAME` và
`ADMIN_PASSWORD` trong biến môi trường của process rồi chạy script. Thông tin đăng nhập và
token không bị in ra hay lưu lại.

Các kiểm tra chỉ đọc trên backend PostgreSQL/Redis local hiện có đều đạt. Các kiểm tra có
thay đổi dữ liệu dùng database riêng `quiktech_admin_checks`, Redis DB 15 và HTTP port 8081,
tắt seed dữ liệu demo. Chúng bao gồm: người dùng thường nhận 403, xác nhận/từ chối hóa đơn,
xác nhận trùng lặp, bắt buộc ghi chú khi từ chối, đổi gói Free/trả phí, và khóa/mở khóa/xóa
một user giả lập.

Khởi động: `mvnw.cmd -Dmaven.test.skip=true package`, rồi chạy file jar với datasource,
Redis database và port phù hợp. Tại thời điểm kiểm chứng, compile toàn bộ test bị lỗi do các
test category/product/unit cũ tham chiếu tới các repository method theo store đã bị gỡ; việc
bỏ qua compile test chỉ để khởi động server tích hợp local. Các test cũ đó đã được xóa sau
đó (2026-10-10).
