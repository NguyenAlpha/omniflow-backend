# Deployment Guide

Hướng dẫn triển khai backend QuikTech POS dưới dạng Spring Boot executable JAR.
Quy trình này phù hợp với máy chủ Linux, VM hoặc dịch vụ cloud có hỗ trợ Java 21.

---

## 1. Kiến trúc triển khai

```text
Client
  |
  v
Reverse proxy / Load balancer (HTTPS)
  |
  v
QuikTech POS API (Java 21, port 8080)
  |-- PostgreSQL 16
  |-- Redis 7
  `-- SMTP server (không bắt buộc)
```

Ứng dụng sử dụng:

- PostgreSQL để lưu dữ liệu nghiệp vụ.
- Flyway để tự động chạy database migration khi ứng dụng khởi động.
- Redis cho cache phân quyền và rate limiting.
- JWT HMAC-SHA256 để xác thực request.
- SMTP để gửi email; ứng dụng vẫn chạy nếu không cấu hình SMTP.

## 2. Yêu cầu

Máy build:

- JDK 21.
- Git.
- Không cần cài Maven toàn hệ thống vì repository có Maven Wrapper.

Môi trường chạy:

- Java Runtime 21.
- PostgreSQL 16 hoặc phiên bản tương thích.
- Redis 7 hoặc dịch vụ Redis tương thích.
- Tối thiểu 1 GB RAM cho API; điều chỉnh theo tải thực tế.
- HTTPS ở reverse proxy hoặc load balancer.

## 3. Chuẩn bị cấu hình

Không commit secret hoặc file `.env` vào Git. Có thể dùng [`.env.example`](../.env.example)
làm danh sách tham khảo, sau đó lưu giá trị thật bằng secret manager của nền tảng hoặc
file chỉ cho service account đọc.

### Biến môi trường chính

| Biến | Bắt buộc | Mô tả |
|:---|:---:|:---|
| `DB_URL` | Có | JDBC URL, ví dụ `jdbc:postgresql://db-host:5432/quiktech_pos` |
| `DB_USERNAME` | Có | User PostgreSQL dành riêng cho ứng dụng |
| `DB_PASSWORD` | Có | Mật khẩu của user PostgreSQL |
| `JWT_SECRET` | Có | Base64 của ít nhất 32 byte ngẫu nhiên |
| `JWT_EXPIRATION` | Không | Thời gian sống access token, đơn vị millisecond |
| `REDIS_HOST` | Có | Hostname của Redis production |
| `REDIS_PORT` | Có | Cổng Redis |
| `REDIS_PASSWORD` | Tùy dịch vụ | Password/access key của Redis |
| `CORS_ORIGINS` | Có | Danh sách origin frontend được phép, phân cách bằng dấu phẩy |
| `MAIL_HOST` | Không | SMTP hostname; bỏ trống để tắt gửi email |
| `MAIL_PORT` | Không | SMTP port |
| `MAIL_USERNAME` | Không | SMTP username |
| `MAIL_PASSWORD` | Không | SMTP password |
| `MAIL_FROM` | Không | Địa chỉ người gửi |
| `SEED_ENABLED` | Không | Chỉ đặt `true` khi chủ động chạy seed |
| `ADMIN_SEED_PASSWORD` | Khi seed admin | Mật khẩu ban đầu của system admin |

Các biến rate limit và seed chi tiết được liệt kê trong [`.env.example`](../.env.example).
Giữ giá trị mặc định cho đến khi có số liệu tải thực tế.

### Tạo JWT secret

Linux/macOS:

```bash
openssl rand -base64 32
```

PowerShell:

```powershell
[Convert]::ToBase64String(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)
```

Không gửi secret qua chat, email hoặc lưu trong shell history.

### Quy tắc production

- Dùng database user riêng, không dùng PostgreSQL superuser.
- Đặt `SEED_ENABLED=false` sau khi seed hoàn tất.
- Chỉ bật `admin.seed.enabled` cho lần tạo admin đầu tiên, sau đó tắt ngay.
- `CORS_ORIGINS` phải là domain cụ thể; không dùng `*` khi gửi credentials.
- JDBC URL production nên yêu cầu SSL nếu database hỗ trợ, ví dụ thêm
  `?sslmode=require`.
- Dịch vụ Redis cloud thường bắt buộc TLS. Phải kiểm tra và bật cấu hình SSL của
  Spring Data Redis trước khi kết nối; không tắt TLS chỉ để ứng dụng kết nối được.

## 4. Build và kiểm tra

Chạy từ thư mục `api`.

Windows:

```powershell
.\mvnw.cmd clean verify
```

Linux/macOS:

```bash
./mvnw clean verify
```

Artifact sau khi build:

```text
target/quiktech-pos-0.0.1-SNAPSHOT.jar
```

Không deploy nếu test hoặc Flyway validation thất bại. Có thể chạy thử artifact:

```bash
java -jar target/quiktech-pos-0.0.1-SNAPSHOT.jar
```

Kiểm tra:

```bash
curl --fail http://localhost:8080/readyz
curl --fail http://localhost:9090/actuator/health   # trên chính máy chủ / mạng nội bộ
```

Kết quả mong đợi là HTTP `200` và trạng thái `UP`.

## 5. Chuẩn bị PostgreSQL

Tạo database và user riêng cho ứng dụng. Ví dụ dưới đây cần được chạy bằng tài khoản
quản trị database:

```sql
CREATE USER quiktech_app WITH PASSWORD '<strong-password>';
CREATE DATABASE quiktech_pos OWNER quiktech_app;
```

Không chạy migration SQL thủ công. Khi API khởi động, Flyway đọc migration trong
`src/main/resources/db/migration` và cập nhật database.

Trước mỗi lần deploy có migration mới:

1. Backup database.
2. Kiểm tra migration trên database staging.
3. Đảm bảo migration tương thích với phiên bản ứng dụng đang chạy nếu triển khai
   rolling update.
4. Không sửa migration đã chạy trên production; tạo migration mới để sửa tiếp.

## 6. Chạy trên Linux bằng systemd

Ví dụ cấu trúc thư mục:

```text
/opt/quiktech-pos/app.jar
/etc/quiktech-pos/quiktech-pos.env
```

File `/etc/quiktech-pos/quiktech-pos.env`:

```dotenv
DB_URL=jdbc:postgresql://db-host:5432/quiktech_pos?sslmode=require
DB_USERNAME=quiktech_app
DB_PASSWORD=<secret>
JWT_SECRET=<base64-secret>
REDIS_HOST=redis-host
REDIS_PORT=6379
REDIS_PASSWORD=<secret>
CORS_ORIGINS=https://app.example.com
SEED_ENABLED=false
MANAGEMENT_SERVER_PORT=9090
```

Actuator (`/actuator/health`, `/actuator/prometheus`) chạy trên `MANAGEMENT_SERVER_PORT`,
**không** mở port này ra Internet — chỉ cho Prometheus/giám sát nội bộ truy cập (firewall
hoặc security group). Port chính chỉ còn `/livez` (process còn sống) và `/readyz` (ứng dụng đã khởi động xong,
sẵn sàng nhận traffic) cho health check của load balancer. Hai probe này không kiểm tra
DB/Redis — chi tiết từng thành phần xem ở `/actuator/health` trên management port.

Giới hạn quyền đọc:

```bash
sudo chown root:quiktech /etc/quiktech-pos/quiktech-pos.env
sudo chmod 640 /etc/quiktech-pos/quiktech-pos.env
```

Tạo `/etc/systemd/system/quiktech-pos.service`:

```ini
[Unit]
Description=QuikTech POS API
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=quiktech
Group=quiktech
WorkingDirectory=/opt/quiktech-pos
EnvironmentFile=/etc/quiktech-pos/quiktech-pos.env
ExecStart=/usr/bin/java -jar /opt/quiktech-pos/app.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
```

Khởi động service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now quiktech-pos
sudo systemctl status quiktech-pos
```

Xem log:

```bash
sudo journalctl -u quiktech-pos -f
```

## 7. Nginx và HTTPS

Ví dụ reverse proxy cơ bản:

```nginx
server {
    listen 80;
    server_name api.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Sau khi kiểm tra cấu hình, cấp chứng chỉ TLS bằng công cụ được hệ thống lựa chọn.
Production chỉ public cổng `443`; không public trực tiếp cổng `8080`.

Nếu Nginx kết nối trực tiếp đến API từ `127.0.0.1`, thêm địa chỉ này vào
`RATE_LIMIT_TRUSTED_PROXIES`. Không tin cậy toàn bộ dải IP nếu không cần thiết.

## 8. Quy trình deploy phiên bản mới

1. Pull đúng commit/tag cần phát hành.
2. Chạy `clean verify`.
3. Backup database nếu release có migration mới.
4. Copy JAR mới vào server với tên tạm.
5. Đổi artifact thành `/opt/quiktech-pos/app.jar`.
6. Restart service.
7. Theo dõi log cho đến khi Flyway và Spring Boot khởi động hoàn tất.
8. Kiểm tra health endpoint và một API read-only.

Ví dụ:

```bash
sudo systemctl restart quiktech-pos
sudo systemctl is-active --quiet quiktech-pos
curl --fail https://api.example.com/readyz
```

Không dùng login hoặc thao tác ghi dữ liệu làm smoke test nếu không có test account và
kịch bản dọn dữ liệu rõ ràng.

## 9. Checklist sau deploy

- [ ] `/readyz` trả HTTP `200`; `https://api.example.com/actuator/health` trả `404` (actuator không public).
- [ ] Log không có lỗi Flyway, database hoặc Redis liên tục.
- [ ] Swagger/OpenAPI chỉ public nếu môi trường cho phép.
- [ ] Frontend origin hợp lệ gọi được API; origin lạ bị CORS chặn.
- [ ] Login và refresh token hoạt động với test account.
- [ ] Rate limit trả header đúng và không tin cậy `X-Forwarded-For` từ client trực tiếp.
- [ ] Email hoạt động nếu SMTP đã được cấu hình.
- [ ] Seed đã được tắt.
- [ ] Dashboard/alert theo dõi CPU, memory, disk và HTTP 5xx đã được bật.

## 10. Rollback

Rollback code bằng cách khôi phục JAR của release gần nhất rồi restart service:

```bash
sudo systemctl restart quiktech-pos
curl --fail https://api.example.com/readyz
```

Rollback application không tự rollback database. Nếu release đã chạy Flyway migration,
chỉ rollback code khi schema mới vẫn tương thích với phiên bản cũ. Với migration phá vỡ
tương thích, cần một migration sửa tiếp hoặc quy trình restore database đã được duyệt.

## 11. Troubleshooting

### Ứng dụng không khởi động

```bash
sudo systemctl status quiktech-pos
sudo journalctl -u quiktech-pos -n 200 --no-pager
```

Kiểm tra Java 21, quyền đọc JAR, quyền đọc file environment và các biến bắt buộc.

### Lỗi JWT secret

`JWT_SECRET` phải là Base64 hợp lệ và sau khi decode có ít nhất 32 byte. Tạo secret mới
theo mục 3. Đổi secret sẽ làm mọi access token hiện tại mất hiệu lực.

### Lỗi database/Flyway

- Kiểm tra DNS, firewall, JDBC URL và SSL mode.
- Kiểm tra user có quyền trên database/schema.
- Đọc bảng `flyway_schema_history` trước khi can thiệp thủ công.

### Lỗi Redis

- Kiểm tra hostname, port, password và yêu cầu TLS.
- Redis lỗi có thể làm cache/rate limiting chuyển sang chế độ giảm cấp, nhưng production
  vẫn phải xử lý nguyên nhân và theo dõi tải PostgreSQL.

### Health trả `DOWN`

Đọc chi tiết response và application log. Không sửa health check thành luôn `UP` để che
lỗi dependency; sửa kết nối hoặc tách readiness/liveness khi triển khai bằng orchestrator.

## 12. Ghi chú cho cloud platform

Trên Azure App Service, AWS Elastic Beanstalk, Google Cloud Run hoặc nền tảng tương tự:

- Thay `systemd` bằng cơ chế runtime của nền tảng.
- Khai báo các biến trên trong phần application settings/environment variables.
- Lưu secret trong secret manager; không đóng gói secret vào JAR.
- Cấu hình health probe tới `/readyz` (readiness) và `/livez` (liveness) trên port chính;
  không public `MANAGEMENT_SERVER_PORT`.
- Giữ PostgreSQL và Redis ngoài container/application instance.
- Xác nhận Java 21 và cổng mà nền tảng cấp cho ứng dụng trước khi deploy.

Quy trình Azure cụ thể sẽ được bổ sung riêng khi subscription, region, SKU và chiến lược
quản lý secret đã được chốt.
