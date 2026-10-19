# QuikTech POS — Backend API

**English** | [Tiếng Việt](README.vi.md)

REST API for **QuikTech POS**: a multi-tenant SaaS for small businesses covering sales, inventory and debt tracking. One business can run many stores, data is isolated per store, and access is role-based.

- Frontend: [NguyenAlpha/quik-tech-web](https://github.com/NguyenAlpha/quik-tech-web)
- Demo: [pos.nhatnguyenhuynh.com](https://pos.nhatnguyenhuynh.com) · [Swagger UI](https://quiktech-pos-api-8217b2.azurewebsites.net/swagger-ui/index.html)

## Tech stack

| Area | Technology |
|:--|:--|
| Language / framework | Java 25, Spring Boot 4.1 (Spring Framework 7) |
| Database | PostgreSQL 16, Spring Data JPA / Hibernate 7, Flyway (`ddl-auto=validate`) |
| Security | Spring Security 7, stateless JWT + refresh token |
| Cache / rate limit | Redis 7, Bucket4j (can be turned off with `REDIS_ENABLED=false`) |
| Other | springdoc-openapi (Swagger), Apache POI (Excel export), Actuator + Prometheus |

## Features

- **Sales:** orders, payments, returns, Excel invoice export
- **Inventory:** products, categories, units, multiple warehouses, transfers, stock adjustments, CSV import
- **Purchasing:** purchase orders to suppliers, receiving stock
- **Customers & suppliers:** debt balances and payment history
- **Businesses & stores:** multiple stores, members and roles (owner, assistant, staff)
- **Plans:** Free / Basic / Pro with limits on stores, staff, products and warehouses
- **System admin:** users, plans, payment accounts, audit log, traffic stats
- Dashboard, notifications, rate limiting, idempotency

## Running locally

**Requirements:** JDK 25, Docker (for PostgreSQL and Redis).

```bash
# 1. PostgreSQL 16 + Redis 7
docker compose up -d

# 2. Create the local config file (ignored by .gitignore)
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

Open the new `application.properties` and set the 2 required values (or pass them as environment variables with the same names):

| Variable | Notes |
|:--|:--|
| `JWT_SECRET` | Generate with `openssl rand -base64 32`. The app stops at startup without it. |
| `ADMIN_SEED_PASSWORD` | Password for the seeded `admin` account (SUPER_ADMIN). |

Everything else (database, Redis, CORS, rate limits…) has local defaults. See [`.env.example`](.env.example) for the full list.

```bash
# 3. Run
./mvnw spring-boot:run
```

| URL | What |
|:--|:--|
| http://localhost:8080 | API |
| http://localhost:8080/swagger-ui/index.html | Swagger UI |
| http://localhost:8080/readyz · `/livez` | Health checks |
| http://localhost:9090/actuator/prometheus | Metrics (separate management port) |

On the first run, Flyway creates the schema and the seeders load sample data:

| Account | Password | Role |
|:--|:--|:--|
| `user1` … `user4` | `password` | Regular users; `user1` owns "Business One" with sample products, orders and customers |
| `admin` | value of `ADMIN_SEED_PASSWORD` | System admin |

Turn off all sample data with `SEED_ENABLED=false`. To run without Redis, set `REDIS_ENABLED=false` (rate limiting falls back to in-memory, single instance only).

## Tests

```bash
./mvnw test
```

Tests use in-memory H2, so PostgreSQL is not needed.

## Deployment

- The demo runs on **Azure App Service + PostgreSQL Flexible Server** and is deployed with [`scripts/azure-deploy.sh`](scripts/azure-deploy.sh). The script must run in **Git Bash** (not directly in PowerShell):
  ```powershell
  & "C:\Program Files\Git\bin\bash.exe" ./scripts/azure-deploy.sh
  ```
  The first run creates the infrastructure; later runs only rebuild and redeploy the JAR. Secrets are kept in `.azure-demo.env` (gitignored; do not delete it).
- General deployment (VM, systemd, reverse proxy, production checklist): [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Project structure

```text
src/main/java/com/quiktech/pos/
├── controller/   REST endpoints
├── service/      business logic
├── repository/   Spring Data JPA
├── entity/       JPA entities
├── dto/          requests / responses
├── security/     JWT, authorization
├── filter/       rate limiting, idempotency
├── config/       configuration, sample data seeders
└── exception/    centralized error handling
src/main/resources/db/migration/   Flyway migrations
docs/                              technical docs
scripts/                           Azure deploy scripts
```

## Documentation

The docs are written in Vietnamese.

| Doc | Topic |
|:--|:--|
| [OVERVIEW](docs/OVERVIEW.md) | Project overview |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | Architecture |
| [FEATURES](docs/FEATURES.md) | Feature list |
| [DEVELOPER_GUIDE](docs/DEVELOPER_GUIDE.md) | Code conventions, JPA patterns |
| [SECURITY](docs/SECURITY.md) · [TOKEN_LIFECYCLE](docs/TOKEN_LIFECYCLE.md) | Security, token lifecycle |
| [RATE_LIMITING](docs/RATE_LIMITING.md) · [REDIS_CACHE](docs/REDIS_CACHE.md) | Rate limiting, caching |
| [ORDER_LIFECYCLE](docs/ORDER_LIFECYCLE.md) · [STORE_MEMBER_LIFECYCLE](docs/STORE_MEMBER_LIFECYCLE.md) | Order and member lifecycles |
| [ERROR_LIFECYCLE](docs/ERROR_LIFECYCLE.md) | How errors flow from services to HTTP responses |
| [DEPLOYMENT](docs/DEPLOYMENT.md) | Deployment |
| [docs/api/](docs/api) | Per-endpoint-group API docs |
| [docs/database/](docs/database) | Schema and database reviews |
