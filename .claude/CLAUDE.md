# CLAUDE.md

File này cung cấp hướng dẫn cho Claude Code (claude.ai/code) khi làm việc trong repository này.

## Build & Chạy ứng dụng

```bash
# Build
./mvnw clean package

# Chạy ứng dụng
./mvnw spring-boot:run

# Chạy toàn bộ test
./mvnw test

# Chạy một class test cụ thể
./mvnw test -Dtest=TênClass

# Chạy một method test cụ thể
./mvnw test -Dtest=TênClass#tênMethod
```

## Công nghệ sử dụng

- **Java 21** + **Spring Boot 3.5.14**
- **Spring Data JPA** + **PostgreSQL** — tầng persistence
- **Flyway** — quản lý migration schema database (đặt file SQL tại `src/main/resources/db/migration/` theo quy tắc đặt tên `V{version}__{mô_tả}.sql`)
- **Spring Security** — xác thực và phân quyền (chưa được cấu hình)
- **Lombok** — dùng `@Data`, `@Builder`, `@RequiredArgsConstructor`, v.v. để giảm boilerplate
- **JUnit 5** — framework test

## Kiến trúc

Kiến trúc phân lớp chuẩn Spring Boot, các package nằm dưới `com.omniflow.backend`:

```
controller/   — REST controller (@RestController)
service/      — Logic nghiệp vụ (@Service)
repository/   — JPA repository (extends JpaRepository)
entity/       — JPA entity (@Entity)
dto/          — DTO cho request/response
config/       — Các class cấu hình Spring
```

Các package này chưa tồn tại — tạo khi phát triển tính năng mới.

## Cấu hình Database

PostgreSQL là database chính. Cần cấu hình kết nối trong `application.properties` (hoặc `application-{profile}.properties`) trước khi chạy:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/omniflow
spring.datasource.username=...
spring.datasource.password=...
spring.jpa.hibernate.ddl-auto=validate
```

Flyway sẽ tự động chạy migration khi khởi động. Mọi thay đổi schema đều phải thông qua file Flyway migration — không dùng `ddl-auto=create` hoặc `ddl-auto=update` ở môi trường ngoài local.
