-- Lưu downgrade dự kiến (hiệu lực cuối chu kỳ hiện tại)
ALTER TABLE subscriptions
    ADD COLUMN pending_plan          VARCHAR(20),
    ADD COLUMN pending_billing_cycle VARCHAR(20);

ALTER TABLE subscriptions
    ADD CONSTRAINT chk_subscriptions_pending_plan CHECK (pending_plan IS NULL OR pending_plan IN ('FREE', 'BASIC', 'PRO')),
    ADD CONSTRAINT chk_subscriptions_pending_cycle CHECK (pending_billing_cycle IS NULL OR pending_billing_cycle IN ('MONTHLY', 'YEARLY'));
