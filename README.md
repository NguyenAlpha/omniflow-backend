# QuikTech POS — Backend API

REST API cho **QuikTech POS**: hệ thống bán hàng, quản lý kho và công nợ dạng multi-tenant SaaS cho doanh nghiệp nhỏ. Một doanh nghiệp có nhiều cửa hàng, dữ liệu tách biệt theo cửa hàng, phân quyền theo vai trò.

- Frontend: [NguyenAlpha/quik-tech-web](https://github.com/NguyenAlpha/quik-tech-web)
- Demo: [pos.nhatnguyenhuynh.com](https://pos.nhatnguyenhuynh.com) · [Swagger UI](https://quiktech-pos-api-8217b2.azurewebsites.net/swagger-ui/index.html)

## Tech stack

| Thành phần | Công nghệ |
|:--|:--|
| Ngôn ngữ / framework | Java 25, Spring Boot 4.1 (Spring Framework 7) |
| Database | PostgreSQL 16, Spring Data JPA / Hibernate 7, Flyway (`ddl-auto=validate`) |
| Bảo mật | Spring Security 7, JWT stateless + refresh token |
| Cache / rate limit | Redis 7, Bucket4j (tắt được bằng `REDIS_ENABLED=false`) |
| Khác | springdoc-openapi (Swagger), Apache POI (xuất Excel), Actuator + Prometheus |

## Chức năng chính

- **Bán hàng:** đơn hàng, thanh toán, trả hàng, xuất hoá đơn Excel
- **Kho:** sản phẩm, danh mục, đơn vị tính, nhiều kho, chuyển kho, điều chỉnh tồn, import CSV
- **Nhập hàng:** đơn nhập từ nhà cung cấp, nhận hàng vào kho
- **Khách hàng & nhà cung cấp:** công nợ và lịch sử thanh toán
- **Doanh nghiệp & cửa hàng:** nhiều cửa hàng, thành viên và vai trò (chủ, trợ lý, nhân viên)
- **Gói dịch vụ:** Free / Basic / Pro với giới hạn cửa hàng, nhân viên, sản phẩm, kho
- **Quản trị hệ thống:** người dùng, gói, tài khoản nhận thanh toán, audit log, thống kê traffic
- Dashboard, thông báo, rate limiting, idempotency

## Chạy local

**Yêu cầu:** JDK 25, Docker (cho PostgreSQL và Redis).

```bash
# 1. PostgreSQL 16 + Redis 7
docker compose up -d

# 2. Tạo file cấu hình local (đã được .gitignore bỏ qua)
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

Mở file `application.properties` vừa tạo và đặt 2 giá trị bắt buộc (hoặc truyền qua biến môi trường cùng tên):

| Biến | Ghi chú |
|:--|:--|
| `JWT_SECRET` | Sinh bằng `openssl rand -base64 32`. Thiếu thì app dừng ngay khi khởi động. |
| `ADMIN_SEED_PASSWORD` | Mật khẩu tài khoản `admin` (SUPER_ADMIN) được seed. |

Các biến khác (database, Redis, CORS, rate limit…) đều có giá trị mặc định cho local. Xem đầy đủ trong [`.env.example`](.env.example).

```bash
# 3. Chạy
./mvnw spring-boot:run
```

| Địa chỉ | Nội dung |
|:--|:--|
| http://localhost:8080 | API |
| http://localhost:8080/swagger-ui/index.html | Swagger UI |
| http://localhost:8080/readyz · `/livez` | Health check |
| http://localhost:9090/actuator/prometheus | Metrics (port quản trị riêng) |

Lần chạy đầu, Flyway tạo schema và seeder nạp dữ liệu mẫu:

| Tài khoản | Mật khẩu | Vai trò |
|:--|:--|:--|
| `user1` … `user4` | `password` | Người dùng; `user1` sở hữu "Business One" với sản phẩm, đơn hàng, khách hàng mẫu |
| `admin` | giá trị `ADMIN_SEED_PASSWORD` | Quản trị hệ thống |

Tắt toàn bộ dữ liệu mẫu bằng `SEED_ENABLED=false`. Muốn chạy không cần Redis thì đặt `REDIS_ENABLED=false` (rate limit chuyển sang in-memory, chỉ dùng cho 1 instance).

## Test

```bash
./mvnw test
```

Test dùng H2 in-memory, không cần PostgreSQL.

## Deploy

- Bản demo chạy trên **Azure App Service + PostgreSQL Flexible Server**, deploy bằng [`scripts/azure-deploy.sh`](scripts/azure-deploy.sh). Script phải chạy bằng **Git Bash** (không chạy trực tiếp trong PowerShell):
  ```powershell
  & "C:\Program Files\Git\bin\bash.exe" ./scripts/azure-deploy.sh
  ```
  Lần đầu script tạo hạ tầng; các lần sau chỉ build và deploy lại JAR. Secret được lưu ở `.azure-demo.env` (gitignored, không được xoá).
- Triển khai tổng quát (VM, systemd, reverse proxy, checklist production): [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Cấu trúc thư mục

```text
src/main/java/com/quiktech/pos/
├── controller/   REST endpoint
├── service/      nghiệp vụ
├── repository/   Spring Data JPA
├── entity/       JPA entity
├── dto/          request / response
├── security/     JWT, phân quyền
├── filter/       rate limit, idempotency
├── config/       cấu hình, seeder dữ liệu mẫu
└── exception/    xử lý lỗi tập trung
src/main/resources/db/migration/   Flyway migration
docs/                              tài liệu kỹ thuật
scripts/                           script deploy Azure
```

## Tài liệu

| Tài liệu | Nội dung |
|:--|:--|
| [OVERVIEW](docs/OVERVIEW.md) | Tổng quan dự án |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | Kiến trúc |
| [FEATURES](docs/FEATURES.md) | Danh sách tính năng |
| [DEVELOPER_GUIDE](docs/DEVELOPER_GUIDE.md) | Quy ước code, pattern JPA |
| [SECURITY](docs/SECURITY.md) · [TOKEN_LIFECYCLE](docs/TOKEN_LIFECYCLE.md) | Bảo mật, vòng đời token |
| [RATE_LIMITING](docs/RATE_LIMITING.md) · [REDIS_CACHE](docs/REDIS_CACHE.md) | Rate limit, cache |
| [ORDER_LIFECYCLE](docs/ORDER_LIFECYCLE.md) · [STORE_MEMBER_LIFECYCLE](docs/STORE_MEMBER_LIFECYCLE.md) | Vòng đời đơn hàng, thành viên |
| [ERROR_LIFECYCLE](docs/ERROR_LIFECYCLE.md) | Luồng xử lý lỗi từ service đến HTTP response |
| [DEPLOYMENT](docs/DEPLOYMENT.md) | Triển khai |
| [docs/api/](docs/api) | Tài liệu từng nhóm endpoint |
| [docs/database/](docs/database) | Schema và review database |
