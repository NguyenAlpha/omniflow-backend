-- ============================================================================
-- V2: Subscriptions & Billing
-- subscriptions, subscription_invoices
--
-- Baseline gộp: subscriptions đã có pending_plan/pending_billing_cycle (downgrade
-- cuối chu kỳ) và expiry_warning_sent_at (chống spam email cảnh báo hết hạn).
-- subscription_invoices đã có các cột xác nhận chuyển khoản thủ công.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

CREATE TABLE subscriptions (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL UNIQUE,
    plan VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    billing_cycle VARCHAR(20),
    max_staff INTEGER,
    max_products INTEGER,
    max_warehouses INTEGER,
    max_stores INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    -- Downgrade dự kiến (hiệu lực cuối chu kỳ hiện tại)
    pending_plan VARCHAR(20),
    pending_billing_cycle VARCHAR(20),
    -- Cờ chống spam email cảnh báo sắp hết hạn: NULL = chưa gửi cho chu kỳ hiện tại;
    -- reset về NULL khi kích hoạt chu kỳ mới (scheduler quét cửa sổ 7 ngày mỗi ngày).
    expiry_warning_sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_subscriptions_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE TABLE subscription_invoices (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL,
    plan VARCHAR(20) NOT NULL,
    billing_cycle VARCHAR(20) NOT NULL,
    amount NUMERIC(15,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_method VARCHAR(20),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    -- Flow xác nhận chuyển khoản thủ công
    bank_transfer_ref VARCHAR(100),  -- mã/nội dung chuyển khoản do business owner gửi lên
    confirmed_by BIGINT,             -- admin user đã xác nhận thanh toán
    admin_note TEXT,                 -- ghi chú của admin khi confirm/reject
    confirmed_at TIMESTAMPTZ,        -- thời điểm admin xác nhận
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_sub_invoices_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_sub_invoices_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES users(id)
);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_sub_invoices_business_id ON subscription_invoices(business_id, created_at DESC);
CREATE INDEX idx_sub_invoices_status ON subscription_invoices(business_id, status) WHERE status = 'PENDING';
CREATE INDEX idx_sub_invoices_confirmed_by ON subscription_invoices(confirmed_by) WHERE confirmed_by IS NOT NULL;
-- Backstop "mỗi business tối đa 1 invoice PENDING tại 1 thời điểm" (chống race check-then-act).
CREATE UNIQUE INDEX ux_subscription_invoices_pending ON subscription_invoices(business_id)
    WHERE status = 'PENDING';

-- ----------------------------------------------------------------------------
-- CHECK constraints
-- ----------------------------------------------------------------------------

ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_plan CHECK (plan IN ('FREE', 'BASIC', 'PRO'));
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED'));
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_billing_cycle CHECK (billing_cycle IN ('MONTHLY', 'YEARLY'));
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_period CHECK (expires_at IS NULL OR expires_at > started_at);
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_pending_plan CHECK (pending_plan IS NULL OR pending_plan IN ('FREE', 'BASIC', 'PRO'));
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_pending_cycle CHECK (pending_billing_cycle IS NULL OR pending_billing_cycle IN ('MONTHLY', 'YEARLY'));

ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_status CHECK (status IN ('PAID', 'PENDING', 'FAILED'));
ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_amount CHECK (amount >= 0);
ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_period CHECK (period_end > period_start);
