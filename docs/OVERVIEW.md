# Project QuikTech POS — POS & Inventory System

## 1. Tổng quan dự án

**QuikTech POS** là hệ thống quản lý bán hàng, kho vận, thống kê đa tenant (multi-tenant B2B SaaS),
tập trung vào tính chính xác dữ liệu tài chính, phân quyền theo vai trò,
và khả năng sync offline (sync chưa triển khai ngay).

- **Mô hình:** Multi-tenant B2B SaaS — 1 hệ thống phục vụ nhiều chủ cửa hàng, dữ liệu tách biệt theo `store_id`
- **Trạng thái:** Phase 1-2 — Core API đang triển khai

---

## 2. Tech Stack

| Thành phần    | Công nghệ                                                                     |
|:--------------|:------------------------------------------------------------------------------|
| **Backend**   | Java 25 / Spring Boot 4.1 (Spring Framework 7, Jackson 3)                     |
| **Database**  | PostgreSQL 16                                                                 |
| **ORM**       | Spring Data JPA + Hibernate 7                                                 |
| **Migration** | Flyway — `ddl-auto=validate`                                                  |
| **Auth**      | Spring Security 7 + OAuth2 Resource Server (Nimbus) — stateless, Hybrid cache |
| **Cache**     | Redis (Spring Data Redis) — store role cache, TTL 5 phút                      |

---

## 3. Lộ trình phát triển

| Giai đoạn   | Nội dung                                 | Trạng thái   |
|:------------|:-----------------------------------------|:-------------|
| **Phase 1** | DB Schema                                | Hoàn thành   |
| **Phase 2** | Core API + Auth                          | Đang làm     |
| **Phase 3** | Docker + Cloud deployment + Read replica | Chưa bắt đầu |
| **Phase 4** | Offline sync (phát triển sau)            | Chưa bắt đầu |
| **Phase 5** | Feature Flag / Feature Toggle            | Chưa bắt đầu |

---

## 4. Danh mục tài liệu

### Kiến trúc & Codebase
| File                                     | Nội dung                                         |
|:-----------------------------------------|:-------------------------------------------------|
| [ARCHITECTURE.md](ARCHITECTURE.md)       | Cấu trúc package, layer design, dependencies     |
| [SECURITY.md](SECURITY.md)               | Hybrid JWT + Redis RBAC, cách dùng @PreAuthorize |
| [DEPLOYMENT.md](DEPLOYMENT.md)           | Build, cấu hình, deploy, health check và rollback |
| [API](api)                               | Tất cả endpoints, access level                   |
| [SUBSCRIPTION.md](api/SUBSCRIPTION.md)  | Luồng nâng cấp gói, invoice, xác nhận CK        |
| [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) | Conventions: JPA, query pattern, naming          |
| [REDIS_CACHE.md](REDIS_CACHE.md)     | Giải thích cơ chế cache Redis          |
| [RATE_LIMITING.md](RATE_LIMITING.md) | Rate limit theo IP / user / tài khoản (Bucket4j + Redis), quota, cấu hình, monitoring |


### Lifecycle & Flow
| File                                                   | Nội dung                                      |
|:-------------------------------------------------------|:----------------------------------------------|
| [LIFECYCLE.md](LIFECYCLE.md)                           | App startup, login flow, JWT request flow     |
| [TOKEN_LIFECYCLE.md](TOKEN_LIFECYCLE.md)               | JWT token — issue, validate, expire, giới hạn |
| [ERROR_LIFECYCLE.md](ERROR_LIFECYCLE.md)               | Exception → HTTP response, mapping table      |
| [STORE_MEMBER_LIFECYCLE.md](STORE_MEMBER_LIFECYCLE.md) | Vòng đời member: add, update role, remove     |
| [ORDER_LIFECYCLE.md](ORDER_LIFECYCLE.md)               | Vòng đời đơn bán: tạo, hoàn thành, thanh toán, huỷ |
### Tính năng
| File                                                   | Nội dung                                      |
|:-------------------------------------------------------|:----------------------------------------------|
| [FEATURES.md](FEATURES.md)               | liệt kê các chức năng                            |
| [ADMIN_OPERATIONS.md](api/ADMIN_OPERATIONS.md) | Thao tác SUPER_ADMIN: audit quản trị, dashboard lưu lượng API, tình trạng hệ thống |
