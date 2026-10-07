# Subscription receiving accounts

These are platform accounts used to collect subscription fees, not merchant/store
payment methods. Runtime configuration is stored in PostgreSQL, not environment variables.

## Administrator API

All administrator endpoints require SUPER_ADMIN. JSON responses use the existing
ApiResult envelope; image GET returns PNG bytes.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/admin/payment-accounts` | List accounts, including archived ones |
| POST | `/api/admin/payment-accounts` | Create account (201); selection is a separate action |
| PUT | `/api/admin/payment-accounts/{id}` | Update account details |
| POST | `/api/admin/payment-accounts/{id}/activate` | Select the default for new invoices |
| POST | `/api/admin/payment-accounts/{id}/archive` | Archive a non-default account |
| POST | `/api/admin/payment-accounts/qr` | Upload PNG/JPEG bytes, Content-Type image/png or image/jpeg (201) |
| GET | `/api/admin/payment-accounts/qr/{key}` | Read uploaded QR image with admin JWT |

Create/update body: `label` (1–100), `bankName` (1–150), `accountNumber`
(4–50 letters/digits, string to preserve leading zeros), `accountHolder` (1–150),
optional `branch` (max 150), nullable `qrImageKey` (max 40), `qrConfirmed` (boolean),
optional `reason` (max 500). Update also requires
the last received `version`. Activate/archive take `{ version, reason? }`.
Responses include these account fields plus `id`, `active`, `archived`, `version`,
`createdAt`, `updatedAt`, `qrImageKey`, `qrImageUrl`. Duplicate non-archived bank name/account-number pairs are
rejected; bank-name comparison ignores case. Missing account: 404. Stale version:
409. Invalid input, editing an archived account or archiving the default: 400.

At most one account is selected through a singleton settings row. All mutations
and invoice snapshot creation lock this row. Archive the default only after
selecting a replacement; there is no delete endpoint. Archived records remain for
history, cannot be edited/selected, and do not alter existing payment instructions.

Each successful mutation writes an audit in the same transaction:
`ADMIN_PAYMENT_ACCOUNT_CREATED`, `ADMIN_PAYMENT_ACCOUNT_UPDATED`,
`ADMIN_PAYMENT_ACCOUNT_ACTIVATED`, `ADMIN_PAYMENT_ACCOUNT_ARCHIVED`.
Activation records the previous receiving account and the new one.

## Invoices and checkout

New invoice responses include `paymentAccountId` and `bankInfo` (bank name,
account number, holder, branch and nullable `qrImageUrl`). Creation copies the receiving details into
the invoice. `UpgradeResponse.bankInfo` contains that same snapshot. Account edits,
switches and archiving never rewrite existing invoice snapshots.

`GET /api/businesses/{businessId}/subscription/bank-info?invoiceId={id}` returns
that invoice's saved details, validates business ownership of the invoice and keeps
the existing owner authorization. Without invoiceId it returns the current default
for availability/preview, or null when none is configured. The availability response
has `qrImageUrl: null`; checkout always reads QR from its invoice. An invoice without a
historical snapshot returns null; callers must not fall back to the current account.
New paid upgrade requests without an available account fail with 503 /
`PAYMENT_ACCOUNT_UNAVAILABLE` and do not create an invoice. Free/downgrade/admin
override operations do not require a receiving account.

## QR upload and storage

Upload uses the raw image body, not multipart or base64. The server reads at most
5 MB + 1 byte, rejects oversized/empty input, decodes PNG/JPEG, limits dimensions
to 4096 × 4096 and re-encodes to PNG without scaling (also capped at 5 MB).
The web UI accepts PNG/JPG/WebP; WebP is converted to PNG in the browser first.
The API does not accept SVG, arbitrary URLs, paths, or native WebP bytes.
Invalid image/key/confirmation input returns 400 `VALIDATION_ERROR`.

An upload returns `{ qrImageKey, qrImageUrl }` inside ApiResult. It does not edit
an account. Submit the key with the account create/update request, plus
`qrConfirmed: true` acknowledging that the administrator scanned and checked the
recipient. The server validates the uploaded key and requires confirmation whenever
an account is saved with a QR. It does not decode banking QR payloads or verify the
bank recipient automatically. Omitted/null `qrImageKey` removes the association on
PUT; send the existing key to retain it. No QR is required for account activation.
Use a reusable bank account QR without a fixed amount/reference.

Every upload has a new UUID filename; files are never overwritten or deleted when
replaced, removed, archived or when a dialog is cancelled. Invoices copy the key
into `payment_qr_image_key` on creation. An invoice created without QR stays without
QR even if the account gets one later. Audit snapshots include QR key/URL changes.

`GET /api/businesses/{businessId}/subscription/invoices/{invoiceId}/qr` returns
that invoice's PNG. It requires business membership (same as invoice detail;
SUPER_ADMIN is allowed), and checks the invoice belongs to that business. The
invoice `bankInfo.qrImageUrl` points to this route. Admin upload previews use the
admin-only route above. These are authenticated relative API URLs, not public file
links: clients fetch with a Bearer token and display a temporary blob URL. Images
use `Cache-Control: no-store`. No image/removed file returns 404 `PAYMENT_QR_NOT_FOUND`.

Files default to `./storage/payment-account-qr` relative to the backend working
directory; override with `PAYMENT_QR_STORAGE_PATH` (absolute path recommended for
deployment). The backend creates the directory on first upload; it needs write
permission. Keep this directory across restarts, mount persistent storage if the
API runs in Docker, and back it up alongside PostgreSQL. The existing Compose file
only runs database/Redis, so a locally run API uses its local filesystem. Storage
is gitignored; no images are stored in the database or committed. Multiple API
instances must share the same storage path. Unused uploads remain on disk in this
initial implementation; do not delete files referenced by account or invoice rows.

## Development baseline and setup

The account/settings tables and invoice snapshot columns are defined directly in
`V2__subscriptions.sql`. This project is in development with a disposable database;
the separate V11 schema migration and V12 Java import have been removed. No account
is seeded or imported from ENV, application.properties or a legacy resource file.
The settings singleton starts with `active_account_id = NULL`.

For an existing development database, recreate the database/volume before using
this revised baseline; it is not an incremental upgrade of the old Flyway history.
Clean the backend build output before rebuilding so a previously compiled V12
class cannot remain on the classpath. Database reset and build execution are left
to the developer; neither was performed as part of this change.

After starting with the fresh schema, sign in as admin, open
`/admin/payment-accounts`, add a receiving account and select **Use for new invoices**.
Subscription payments become available after selection. All later changes happen
on the web and remain in the database across restarts. Old `subscription.payment.*`
configuration is unused and can be removed from local configuration.

Deploy the backend before the frontend. The frontend must use invoice-specific
details on reopen and must not display the old fixed `/qr.png` for arbitrary banks.

Implementation only: no backend tests were added, and migrations, builds, servers
or runtime checks were not executed for this change, as requested by the user.
