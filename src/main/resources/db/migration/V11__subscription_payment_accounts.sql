CREATE TABLE subscription_payment_accounts (
    id BIGSERIAL PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    bank_name VARCHAR(150) NOT NULL,
    account_number VARCHAR(50) NOT NULL,
    account_holder VARCHAR(150) NOT NULL,
    branch VARCHAR(150) NOT NULL DEFAULT '',
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- A singleton pointer allows at most one default and provides a lock for switches/snapshots.
CREATE TABLE subscription_payment_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    active_account_id BIGINT REFERENCES subscription_payment_accounts(id)
);
INSERT INTO subscription_payment_settings (id) VALUES (1);

ALTER TABLE subscription_invoices
    ADD COLUMN payment_account_id BIGINT REFERENCES subscription_payment_accounts(id),
    ADD COLUMN payment_bank_name VARCHAR(150),
    ADD COLUMN payment_account_number VARCHAR(50),
    ADD COLUMN payment_account_holder VARCHAR(150),
    ADD COLUMN payment_branch VARCHAR(150);
