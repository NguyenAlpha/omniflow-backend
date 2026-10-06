# Admin operations

All `/api/admin/**` endpoints require a valid JWT and `SUPER_ADMIN`. Responses
use the existing `{ success, data, error }` envelope.

## GET `/api/admin/session`

Returns the current `UserSummaryResponse` after checking the administrator role.
The web client calls this before storing an admin login and when opening the
admin panel. A normal user receives 403; a missing/invalid token receives 401.
The server remains authoritative for every protected operation.

## GET `/api/admin/businesses/{businessId}`

Returns `{ business, subscription, stores }` using existing response DTOs. Stores
are scoped to the requested business and exclude soft-deleted records. Missing
businesses return 404; non-administrators return 403. Invoice history remains at
`GET /api/businesses/{businessId}/subscription/invoices?page=0&size=20`.

## Live verification (2026-10-06)

The web repository contains `scripts/check-admin-live.mjs`. Set `ADMIN_USERNAME`
and `ADMIN_PASSWORD` in the process environment, then run the script. Credentials
and tokens are not printed or saved.

Read-only checks against the existing local PostgreSQL/Redis backend passed.
Mutation checks used a separate `quiktech_admin_checks` database, Redis DB 15 and
HTTP port 8081, with demo seeding disabled. They covered normal-user 403, invoice
confirm/reject, duplicate confirmation, required rejection note, Free/paid plan
changes, and locking/unlocking/deleting a synthetic user.

Startup: `mvnw.cmd -Dmaven.test.skip=true package`, then run the jar with the
appropriate datasource, Redis database and port. The default full test compilation
currently fails in pre-existing category/product/unit tests referencing old
store-scoped repository methods; skipping compilation is for starting the local
integration server, not a claim that those tests pass.
