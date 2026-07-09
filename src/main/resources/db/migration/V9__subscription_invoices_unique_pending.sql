-- Backstop DB cho quy tắc "mỗi business tối đa 1 invoice PENDING tại 1 thời điểm".
--
-- SubscriptionService.requestUpgrade check findPendingByBusinessId rồi mới INSERT
-- (check-then-act) — 2 request song song đều qua check → 2 invoice PENDING cùng lúc,
-- admin có thể confirm cả hai. Index này khiến request thứ hai fail khi INSERT.
CREATE UNIQUE INDEX ux_subscription_invoices_pending ON subscription_invoices(business_id)
    WHERE status = 'PENDING';
