# Project QuikTech — POS & Inventory System

## 1. Tổng quan dự án

**QuikTech** là hệ thống quản lý bán hàng, kho vận, thống kê đa tenant (multi-tenant B2B SaaS),
tập trung vào tính chính xác dữ liệu tài chính, phân quyền theo vai trò,
và khả năng sync offline (sync chưa triển khai ngay).

- **Mô hình:** Multi-tenant B2B SaaS — 1 hệ thống phục vụ nhiều chủ cửa hàng, dữ liệu tách biệt theo `store_id`
- **Trạng thái:** Phase 1-2 — Core API đang triển khai

---

## 2. Tech Stack

| Thành phần    | Công nghệ                                                                     |
|:--------------|:------------------------------------------------------------------------------|
| **Backend**   | Java 21 / Spring Boot 3.5                                                     |
| **Database**  | PostgreSQL 16                                                                 |
| **ORM**       | Spring Data JPA + Hibernate 6                                                 |
| **Migration** | Flyway — `ddl-auto=validate`                                                  |
| **Auth**      | Spring Security 6 + OAuth2 Resource Server (Nimbus) — stateless, Hybrid cache |
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
| [ENTITY_MODEL.md](ENTITY_MODEL.md)       | 26 entities theo domain, DB conventions          |
| [SECURITY.md](SECURITY.md)               | Hybrid JWT + Redis RBAC, cách dùng @PreAuthorize |
| [API.md](API.md)                         | Tất cả endpoints, access level                   |
| [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) | Conventions: JPA, query pattern, naming          |

### Lifecycle & Flow
| File                                                   | Nội dung                                      |
|:-------------------------------------------------------|:----------------------------------------------|
| [LIFECYCLE.md](LIFECYCLE.md)                           | App startup, login flow, JWT request flow     |
| [TOKEN_LIFECYCLE.md](TOKEN_LIFECYCLE.md)               | JWT token — issue, validate, expire, giới hạn |
| [ERROR_LIFECYCLE.md](ERROR_LIFECYCLE.md)               | Exception → HTTP response, mapping table      |
| [STORE_MEMBER_LIFECYCLE.md](STORE_MEMBER_LIFECYCLE.md) | Vòng đời member: add, update role, remove     |
