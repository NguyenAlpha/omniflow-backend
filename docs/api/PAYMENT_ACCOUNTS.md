# Subscription receiving accounts

These are platform accounts used to collect subscription fees, not merchant/store
payment methods. Runtime configuration is stored in PostgreSQL, not environment variables.

## Administrator API

All endpoints require SUPER_ADMIN and use the existing ApiResult envelope.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/admin/payment-accounts` | List accounts, including archived ones |
| POST | `/api/admin/payment-accounts` | Create account (201); selection is a separate action |
| PUT | `/api/admin/payment-accounts/{id}` | Update account details |
| POST | `/api/admin/payment-accounts/{id}/activate` | Select the default for new invoices |
| POST | `/api/admin/payment-accounts/{id}/archive` | Archive a non-default account |

Create/update body: `label` (1–100), `bankName` (1–150), `accountNumber`
(4–50 letters/digits, string to preserve leading zeros), `accountHolder` (1–150),
optional `branch` (max 150), optional `reason` (max 500). Update also requires
the last received `version`. Activate/archive take `{ version, reason? }`.
Responses include these account fields plus `id`, `active`, `archived`, `version`,
`createdAt`, `updatedAt`. Duplicate non-archived bank name/account-number pairs are
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

New invoice responses include `paymentAccountId` and `bankInfo` (the same four
fields as BankTransferInfoResponse). Creation copies the receiving details into
the invoice. `UpgradeResponse.bankInfo` contains that same snapshot. Account edits,
switches and archiving never rewrite existing invoice snapshots.

`GET /api/businesses/{businessId}/subscription/bank-info?invoiceId={id}` returns
that invoice's saved details, validates business ownership of the invoice and keeps
the existing owner authorization. Without invoiceId it returns the current default
for availability/preview, or null when none is configured. An invoice without a
historical snapshot returns null; callers must not fall back to the current account.
New paid upgrade requests without an available account fail with 503 /
`PAYMENT_ACCOUNT_UNAVAILABLE` and do not create an invoice. Free/downgrade/admin
override operations do not require a receiving account.

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
