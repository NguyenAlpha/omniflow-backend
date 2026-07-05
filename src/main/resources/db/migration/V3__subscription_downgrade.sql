-- Lưu downgrade dự kiến (hiệu lực cuối chu kỳ hiện tại)
ALTER TABLE subscriptions
    ADD COLUMN pending_plan          VARCHAR(20),
    ADD COLUMN pending_billing_cycle VARCHAR(20);
