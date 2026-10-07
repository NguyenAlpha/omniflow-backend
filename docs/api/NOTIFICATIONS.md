# In-app notifications

Store-context routes require a current store member. Each query also checks the
business/store scope. Inventory events belong to one store; subscription events
are visible only to the business OWNER (SUPER_ADMIN bypass follows the existing
authorization evaluator). BUSINESS_MANAGER, MANAGER and STAFF do not see
subscription counts, events or read actions. Read receipts belong to the JWT user;
clients cannot supply another user ID. A business event has one read state per
user across that business's stores. Current authorized members can view history.

All responses use ApiResult. Base: `/api/stores/{storeId}/notifications`.

| Method | Suffix | Contract |
| --- | --- | --- |
| GET | `/summary` | `{lowStockCount, pendingInvoiceCount, unreadCount, latestId}` |
| GET | empty | `?cursor=&size=20&unreadOnly=false`; `{content, nextCursor}` |
| GET | `/low-stock` | Current lowest-stock entries, limited to 10 in SQL |
| POST | `/{id}/read` | Idempotent; only a visible notification can be marked |
| POST | `/read-all` | `{throughId: latestId}`; only currently visible events up to this ID |

List entries contain `id`, `type`, `subject`, `detail`, `targetPath`, `createdAt`,
`read`, `resolved`. Size must be 1–50; cursor is a positive exclusive ID, ordered
by ID descending. Unknown/out-of-scope events return 404 NOTIFICATION_NOT_FOUND;
non-members return 403. Invalid pagination/read boundary returns 400. The read-all
boundary keeps newer events unread while the user acts on an older summary.

`unreadCount` counts only unread, unresolved events. Live low-stock/pending counts
are separate and do not decrease when an event is read. Resolved alerts remain in
history and are treated as read. Low stock counts inventory/product/warehouse
combinations, not distinct products. Deleted or inactive products, warehouses,
stores and businesses are excluded. The legacy summary fields/routes remain.

## Event collection and delivery

`NotificationProjector` polls committed database state every 60 seconds, starting
10 seconds after startup (`notifications.refresh-ms`, `notifications.initial-delay-ms`).
It also runs with no browser open. PostgreSQL transaction advisory locking permits
one collector across API instances; unique event keys make retries idempotent.
The frontend polls summary every 60 seconds while visible and refreshes on focus
and opening the bell. Delivery can take roughly two polling intervals. This is
in-app polling, not browser push/WebSocket delivery.

| Type | Source and deduplication |
| --- | --- |
| LOW_STOCK | Quantity below minimum for an active inventory row; one event per observed low-stock episode |
| INVOICE_PAID | Confirmed subscription invoice; once per invoice/status |
| INVOICE_FAILED | Rejected or automatically expired pending invoice; once per invoice/status, including reason |
| SUBSCRIPTION_EXPIRING | Active paid subscription expires within 7 days; once per expiry timestamp |
| SUBSCRIPTION_EXPIRED | Paid subscription expiry reached; once per expiry timestamp |

The initial scan imports existing terminal invoices and current alerts. Invoice
events preserve their source timestamp; cursor order follows notification IDs.
The collector does not change subscriptions, stock, invoices or email behavior.
It observes all committed writes, including imports/sync, without depending on a
particular inventory controller. Transient stock changes that recover between
scans are not recorded. Recovery observed by a scan resolves the old stock event
and releases its active key; a later observed shortage creates a new event.
Expiry warnings resolve after expiry/renewal/plan changes; expired notices resolve
when their cycle is replaced. Product/warehouse names and quantities are snapshots
at detection time; current stock is always read from the live inventory endpoint.

The two tables, `notifications` and `notification_reads`, are in the existing V8
development baseline. Reset the disposable development database before using this
changed baseline; this is not an incremental migration for deployed data. No DB
reset, application run, build or tests were executed, and no backend tests were
added, per the user's instructions. Events/read history have no automatic retention
cleanup in this version.
