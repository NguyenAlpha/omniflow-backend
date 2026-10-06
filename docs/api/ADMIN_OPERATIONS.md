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

## Invoice history and pending count

`GET /api/admin/subscriptions/invoices` accepts optional `status` (PENDING, PAID,
FAILED), positive `businessId`, `q` (at most 100 characters), and pageable `page` /
`size`. Search matches business names/transfer references case-insensitively or
exact numeric invoice/business IDs. `%` and `_` are literal search characters.
Results use the existing invoice DTO and Spring Page envelope, sorted by
`createdAt DESC, id DESC`; page size is capped at 100. Invalid parameter types
return 400 with `VALIDATION_ERROR`.

`GET /api/admin/subscriptions/invoices/pending/count` returns the current numeric
pending count. Both endpoints require SUPER_ADMIN.

## Administrator audit

`GET /api/admin/audit-logs?size=20` returns `{ content, nextCursor }`. Optional
filters: `businessId`, `actorId`, `action` and `beforeId` (the previous response's
`nextCursor`). IDs must be positive; size is 1–100. Results use descending ID
keyset pagination. Only SUPER_ADMIN can read this endpoint.

Recorded actions: `ADMIN_PLAN_CHANGED`, `ADMIN_INVOICE_CONFIRMED`,
`ADMIN_INVOICE_REJECTED`, `ADMIN_USER_STATUS_CHANGED`, `ADMIN_USER_DELETED`.
Entries include actor ID/name captured at the time, timestamp, entity, business,
reason and before/after snapshots. Invoice confirmation includes both invoice and
subscription changes. User snapshots contain only ID, username, active state and
deletion timestamp. Passwords, tokens and whole user entities are never serialized.

Plan/status requests accept optional `reason` (max 500 characters). DELETE user
accepts an optional JSON body `{ "reason": "..." }`; existing body-less requests
remain supported. Invoice reasons use the existing `adminNote` field.

The service writes synchronously into the existing `audit_logs` table within the
mutation transaction (`MANDATORY` propagation). Failed writes roll back the
mutation. No schema migration or historical backfill is needed. The generic async
audit logger remains separate. Audit coverage starts after this version is deployed;
it covers the five actions listed above, not every system/merchant operation.
Invoice, subscription override and account status/delete mutations lock their
target rows to serialize concurrent administrator changes.

Run `node scripts/check-admin-audit-rollback.mjs` with admin credentials in the
environment to exercise a real PostgreSQL insert failure and transaction rollback.
This script is fixed to isolated port 8081/database `quiktech_admin_checks` and the
local `quiktech-pos-db` container. It installs a temporary check constraint targeting
one unique reason, removes it in `finally`, and soft-deletes its synthetic user.

Validated on 2026-10-06 against PostgreSQL: actor/reason/before-after snapshots,
normal-user 403, invalid filters/oversized reasons 400, cursor boundaries, no audit
for failed actions, concurrent confirmation applying exactly once, and complete
subscription rollback on forced audit-insert failure. The temporary constraint
was removed after verification. No real business records were changed.

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
