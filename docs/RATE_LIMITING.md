# Rate Limiting

Tài liệu này mô tả cơ chế giới hạn request của backend QuikTech POS. Cơ chế dùng
Bucket4j với Redis để quota được chia sẻ giữa nhiều instance backend.

## Mục tiêu

- Chặn brute-force ở login, register và refresh token.
- Bảo vệ API khi một client gửi quá nhiều request.
- Tách quota theo user cho các máy POS dùng chung mạng; trần IP vẫn là giới hạn dùng chung.
- Hạn chế endpoint tốn tài nguyên như import, export và điều chỉnh/chuyển kho hàng loạt.

## Kiến trúc hai lớp

```text
Client request
  |
  v
CorsFilter (servlet filter, trước limiter và Spring Security)
  |
  v
RateLimitFilter (trước Spring Security)
  |- quota theo IP cho login/register/refresh
  `- trần IP rộng cho mọi /api/**
  |
  v
BearerTokenAuthenticationFilter
  |- kiểm tra JWT
  `- tạo UserPrincipal có userId
  |
  v
AuthenticatedRateLimitFilter
  |- quota mặc định theo userId
  `- quota riêng cho import/export/inventory bulk/đổi mật khẩu
  |
  v
Controller
```

Lớp IP xử lý request chưa xác thực và flood thô. Lớp user chỉ chạy sau khi JWT
hợp lệ, do đó mỗi nhân viên có quota riêng ngay cả khi cùng Wi-Fi/NAT của store.

## Quota mặc định

| Policy | Key Redis | Phạm vi | Mặc định |
|---|---|---|---:|
| Login | `rl:login:{ip}` | `POST /api/auth/login` | 10/phút |
| Login sai theo tài khoản | `rl:login-account:{user:<id> \| name:<sha256>}` | lần **sai mật khẩu** của `POST /api/auth/login` | 5/15 phút |
| Register | `rl:register:{ip}` | `POST /api/auth/register` | 5/phút |
| Refresh | `rl:refresh:{ip}` | `POST /api/auth/refresh` | 20/phút |
| API IP ceiling | `rl:ip:api:{ip}` | mọi `/api/**` khác | 1.200/phút |
| API user | `rl:user:api:{userId}` | API JWT không có rule riêng | 300/phút |
| Product import | `rl:user:import-products:{userId}` | `POST /api/businesses/{businessId}/products/import` | 5/10 phút |
| Export | `rl:user:export:{userId}` | mọi `GET`/`HEAD /api/stores/{storeId}/export/**` | 10/10 phút |
| Inventory bulk | `rl:user:inventory-bulk:{userId}` | `POST /api/stores/{storeId}/inventory/adjust/bulk` và `/transfer/bulk` (chung bucket) | 10/10 phút |
| Change password | `rl:user:change-password:{userId}` | `PATCH /api/users/me/password` | 5/10 phút |

Rule cụ thể thay thế quota user mặc định cho request đó. Request vẫn chịu trần IP
trước Spring Security, trừ login/register/refresh vốn có quota IP chặt hơn.
`HEAD` dùng chung bucket export với `GET`, vì Spring MVC vẫn thực thi controller
`@GetMapping` khi nhận `HEAD`. Request preflight CORS không tiêu thụ quota.

## Cấu hình

Danh sách biến môi trường nằm trong [`.env.example`](../.env.example). Spring
Boot map tên biến in hoa có dấu gạch dưới về property dạng kebab case, ví dụ:

```dotenv
RATE_LIMIT_API_IP_MAX_REQUESTS=1200
RATE_LIMIT_API_IP_WINDOW_SECONDS=60
RATE_LIMIT_API_USER_MAX_REQUESTS=300
RATE_LIMIT_API_USER_WINDOW_SECONDS=60
RATE_LIMIT_IMPORT_PRODUCTS_USER_MAX_REQUESTS=5
RATE_LIMIT_IMPORT_PRODUCTS_USER_WINDOW_SECONDS=600
RATE_LIMIT_REDIS_TIMEOUT_MILLIS=200
RATE_LIMIT_REDIS_COOLDOWN_MILLIS=5000
RATE_LIMIT_CONFIG_VERSION=1
```

Không nên giảm quota IP tổng quá thấp khi nhiều máy POS dùng chung một public IP.
Theo dõi metric trước, sau đó điều chỉnh dần theo lưu lượng production.

### Thay đổi quota khi Redis đã có bucket

Mỗi lần đổi capacity hoặc window, **tăng `RATE_LIMIT_CONFIG_VERSION`** và triển
khai cùng quota/version cho tất cả instance của release đó. Ví dụ đổi quota user
từ 300 xuống 200 thì tăng version từ 1 lên 2. Chỉ sửa quota mà giữ nguyên version
không cập nhật bucket đã tồn tại. Biến môi trường phải được truyền vào process;
file `.env` không tự được Spring Boot đọc.

`RateLimitService` dùng implicit configuration replacement của Bucket4j và
`PROPORTIONALLY`: giữ tỷ lệ token còn lại, không cấp lại toàn bộ quota khi deploy.
Bucket cũ chưa có version cũng được nâng cấp khi nhận request. Trong rolling
deployment, instance version thấp hơn không ghi đè cấu hình mới; header limit
lấy từ cấu hình thực tế của bucket. Bucket chưa được instance mới truy cập vẫn
dùng quota cũ cho đến lần truy cập đầu tiên từ instance mới.

Khi rollback quota, triển khai giá trị cũ với **version mới lớn hơn**, không giảm
version. Không cần xóa Redis key. Version, timeout và cooldown phải là số dương.

## Giới hạn đăng nhập sai theo tài khoản

Quota login theo IP không chặn được dò mật khẩu một tài khoản từ nhiều IP (botnet).
`LoginAttemptLimiter` (gọi trong `AuthService.login`) đếm số lần **sai mật khẩu** theo
tài khoản:

- Trước khi xác thực chỉ *xem* bucket (không trừ); sai mật khẩu mới trừ 1 lượt. Đăng nhập
  đúng, tài khoản bị khóa (`DisabledException`) không trừ.
- Key là `user:<userId>` nếu tài khoản tồn tại (username và email dùng chung bucket);
  không tồn tại thì `name:<sha256(chuỗi đã nhập, lowercase)>` — chuỗi dài tùy ý không
  thành key Redis, và vẫn giới hạn dò tài khoản không có thật.
- Hết lượt → `429 RATE_LIMIT_EXCEEDED` với cùng header như các quota khác, tới khi bucket
  nạp lại. **Không khóa hẳn tài khoản**: người khác cố tình nhập sai chỉ làm chủ tài khoản
  phải chờ hết cửa sổ, không khóa vĩnh viễn được.

```dotenv
RATE_LIMIT_LOGIN_ACCOUNT_MAX_FAILURES=5
RATE_LIMIT_LOGIN_ACCOUNT_WINDOW_SECONDS=900
```

## Reverse proxy và IP client

`X-Forwarded-For` có thể bị client giả mạo. Backend chỉ đọc header này khi IP
kết nối trực tiếp (`remoteAddr`) nằm trong `RATE_LIMIT_TRUSTED_PROXIES`:

```dotenv
RATE_LIMIT_TRUSTED_PROXIES=10.0.0.10,10.0.0.11
```

Điền IP nội bộ của Nginx/load balancer, không điền dải IP client hay giá trị
`0.0.0.0/0`. Nếu backend được gọi trực tiếp, để biến này rỗng. Audit log cũng
dùng cùng `ClientIpResolver`, nên IP trong log và rate-limit key nhất quán.

Biến nhận cả IP lẻ lẫn dải CIDR (VD `10.0.0.0/8` cho mạng nội bộ Docker/k8s có IP
thay đổi). Khi có nhiều lớp proxy (VD CDN → Nginx → app), khai báo tất cả các lớp:
`X-Forwarded-For` được duyệt từ phải sang trái, bỏ qua các entry là proxy tin cậy,
entry đầu tiên không tin cậy là IP client. Thiếu một lớp thì IP của lớp đó bị coi
là client và mọi người dùng sau nó chung một bucket.

Bucket theo IP gom IPv6 theo dải **/64** (nhà mạng thường cấp cả dải /64 cho một
thuê bao — tính từng địa chỉ thì đổi địa chỉ là né được quota). IPv4 và IPv4-mapped
IPv6 giữ nguyên. Audit log vẫn ghi đầy đủ địa chỉ.

## Phản hồi khi bị giới hạn

Khi bucket hết token, API trả HTTP `429 Too Many Requests`:

```http
Retry-After: 42
RateLimit-Limit: 300
RateLimit-Remaining: 0
RateLimit-Reset: 42
Content-Type: application/json
```

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Too many requests. Please try again later.",
    "field": null
  }
}
```

`CorsFilter` chạy trước limiter nên response 429 theo IP và user đều có CORS
header cho origin được phép. `Retry-After` và cả ba header `RateLimit-*` được
expose để JavaScript khác origin có thể đọc. Origin không được phép vẫn bị chặn.

Frontend nên hiển thị lỗi này và không tự retry request ghi dữ liệu hoặc export.
`Retry-After` là số giây client nên chờ trước khi thử lại. Web đã xử lý: `ApiError`
đọc `Retry-After`, `t.errors.RATE_LIMIT_RETRY_AFTER` hiện "thử lại sau N giây",
`useRateLimitCooldown` khóa nút đăng nhập/import/export tới hết thời gian chờ, và
refresh token gặp 429 không bị gửi lại liên tục (xem web `docs/RATE_LIMITING.md`).

## Redis, availability và monitoring

Bucket state lưu trong Redis qua `LettuceBasedProxyManager`. Mỗi lần kiểm tra
quota có deadline mặc định **200 ms** ở Bucket4j, thay vì chờ timeout Redis mặc
định một phút. Nếu kiểm tra lỗi hoặc timeout, limiter **fail-open**: request đi
tiếp và cả hai lớp IP/user của instance đó bỏ qua Redis trong **5 giây**. Sau đó
instance thử Redis trở lại; nếu vẫn lỗi thì bắt đầu cooldown mới. Các request
đã đang kiểm tra trước thời điểm phát hiện lỗi vẫn có thể chờ tới deadline.

**Ngoại lệ — quota chống brute-force:** `login`, `register`, `refresh` (theo IP) và
`login-account` không fail-open. Khi Redis lỗi/cooldown, chúng chuyển sang bucket
Bucket4j trong bộ nhớ của từng instance (LRU tối đa 10.000 key) — vẫn chặn, chỉ không
chia sẻ giữa các instance, nên quota thực tế là `capacity × số instance` trong lúc
Redis sập. Khi Redis hồi phục, quota quay lại dùng bucket Redis.

Cooldown là trạng thái cục bộ mỗi instance, không phải quota; quota vẫn nằm
trong Redis. Warning được hạn chế theo đợt lỗi, không ghi một stack trace cho
mỗi request bị bỏ qua. Các cơ chế này áp dụng cho kiểm tra quota sau khi app
khởi động, không thay thế timeout của các Redis consumer khác. Kết nối Redis
vẫn được tạo lúc khởi động nên Redis không sẵn sàng có thể làm startup thất bại.

Vì fail-open không thể thay thế bảo vệ ở biên mạng, production nên cấu hình
thêm rate limit thô tại Nginx, CDN hoặc WAF trước khi traffic vào Spring Boot.

Metric Prometheus:

```text
rate_limit_requests_total{scope="ip|user|account",policy="...",outcome="allowed|blocked|error|bypassed|fallback_blocked"}
```

Không có IP, userId, URL có ID hoặc request header trong labels để tránh lộ dữ
liệu cá nhân và tránh high-cardinality trong Prometheus.
`error` là thao tác quota thất bại; `bypassed` là bỏ qua trong cooldown. Cần cảnh
báo cho cả hai trạng thái để phát hiện lúc hệ thống đang mất bảo vệ rate limit.

Ví dụ truy vấn số request bị chặn trong 5 phút:

```promql
sum by (scope, policy) (
  increase(rate_limit_requests_total{outcome="blocked"}[5m])
)
```

## Kiểm thử

Chạy riêng bộ test rate limit (không cần Redis/PostgreSQL đang chạy):

```powershell
.\mvnw.cmd -Prate-limit-tests test
```

Profile chỉ giới hạn source/test của lần chạy này; lệnh `mvnw test` mặc định vẫn
chạy toàn bộ suite. Hiện các test nghiệp vụ cũ chưa đồng bộ DTO/repository nên
lệnh mặc định có thể dừng ở test compilation, độc lập với rate limit.

`RateLimitIntegrationTest` chạy CORS + limiter IP + Spring Security với JWT ký
thật + limiter user + controller export. Kiểm tra 429/CORS, preflight, origin bị
cấm, GET/HEAD chung quota, token sai, quota tách user và không chạy filter user
hai lần. `RateLimitServiceTest` dùng Bucket4j thật với Redis transport mô phỏng
CAS để kiểm tra version migration, instance cũ không downgrade, token được giữ
theo tỷ lệ, concurrency, refill, timeout và cooldown/recovery. Đây không phải
test kết nối Redis thật hay thử tải production.

`ClientIpResolverTest` kiểm tra ba tình huống bảo mật:

- Client gọi thẳng không thể giả `X-Forwarded-For`.
- Proxy tin cậy được phép cung cấp IP client.
- Proxy không gửi header thì fallback về `remoteAddr`.

Khi thêm policy mới, cần kiểm tra ít nhất: đúng key Redis, quota tách biệt giữa
hai user/IP, trả 429 sau khi hết quota, `Retry-After`, và hành vi sau refill.
