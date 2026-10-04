# Rate Limiting

Tài liệu này mô tả cơ chế giới hạn request của backend QuikTech POS. Cơ chế dùng
Bucket4j với Redis để quota được chia sẻ giữa nhiều instance backend.

## Mục tiêu

- Chặn brute-force ở login, register và refresh token.
- Bảo vệ API khi một client gửi quá nhiều request.
- Không làm các máy POS dùng chung một mạng tự chặn nhau.
- Hạn chế endpoint tốn tài nguyên như import và export.

## Kiến trúc hai lớp

```text
Client request
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
  `- quota riêng cho import/export/đổi mật khẩu
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
| Register | `rl:register:{ip}` | `POST /api/auth/register` | 5/phút |
| Refresh | `rl:refresh:{ip}` | `POST /api/auth/refresh` | 20/phút |
| API IP ceiling | `rl:ip:api:{ip}` | mọi `/api/**` khác | 1.200/phút |
| API user | `rl:user:api:{userId}` | API JWT không có rule riêng | 300/phút |
| Product import | `rl:user:import-products:{userId}` | `POST /api/businesses/{businessId}/products/import` | 5/10 phút |
| Export | `rl:user:export:{userId}` | mọi `GET /api/stores/{storeId}/export/**` | 10/10 phút |
| Change password | `rl:user:change-password:{userId}` | `PATCH /api/users/me/password` | 5/10 phút |

Rule cụ thể thay thế quota user mặc định cho request đó. Request vẫn chịu trần IP
trước Spring Security, trừ login/register/refresh vốn có quota IP chặt hơn.

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
```

Không nên giảm quota IP tổng quá thấp khi nhiều máy POS dùng chung một public IP.
Theo dõi metric trước, sau đó điều chỉnh dần theo lưu lượng production.

## Reverse proxy và IP client

`X-Forwarded-For` có thể bị client giả mạo. Backend chỉ đọc header này khi IP
kết nối trực tiếp (`remoteAddr`) nằm trong `RATE_LIMIT_TRUSTED_PROXIES`:

```dotenv
RATE_LIMIT_TRUSTED_PROXIES=10.0.0.10,10.0.0.11
```

Điền IP nội bộ của Nginx/load balancer, không điền dải IP client hay giá trị
`0.0.0.0/0`. Nếu backend được gọi trực tiếp, để biến này rỗng. Audit log cũng
dùng cùng `ClientIpResolver`, nên IP trong log và rate-limit key nhất quán.

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

Frontend hiển thị lỗi này cho người dùng và không tự retry request ghi dữ liệu
hoặc export. `Retry-After` là số giây client nên chờ trước khi thử lại.

## Redis, availability và monitoring

Bucket state lưu trong Redis qua `LettuceBasedProxyManager`. Khi Redis lỗi,
limiter **fail-open**: log warning và cho request đi qua, tránh làm POS trả 500
hoặc ngừng đăng nhập chỉ vì Redis không sẵn sàng.

Vì fail-open không thể thay thế bảo vệ ở biên mạng, production nên cấu hình
thêm rate limit thô tại Nginx, CDN hoặc WAF trước khi traffic vào Spring Boot.

Metric Prometheus:

```text
rate_limit_requests_total{scope="ip|user",policy="...",outcome="allowed|blocked|error"}
```

Không có IP, userId, URL có ID hoặc request header trong labels để tránh lộ dữ
liệu cá nhân và tránh high-cardinality trong Prometheus.

Ví dụ truy vấn số request bị chặn trong 5 phút:

```promql
sum by (scope, policy) (
  increase(rate_limit_requests_total{outcome="blocked"}[5m])
)
```

## Kiểm thử

`ClientIpResolverTest` kiểm tra ba tình huống bảo mật:

- Client gọi thẳng không thể giả `X-Forwarded-For`.
- Proxy tin cậy được phép cung cấp IP client.
- Proxy không gửi header thì fallback về `remoteAddr`.

Khi thêm policy mới, cần kiểm tra ít nhất: đúng key Redis, quota tách biệt giữa
hai user/IP, trả 429 sau khi hết quota, `Retry-After`, và hành vi sau refill.
