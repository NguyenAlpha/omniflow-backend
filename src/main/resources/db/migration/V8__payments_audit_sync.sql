-- ============================================================================
-- V8: Payments, Audit & Sync
-- payments, audit_logs, sync_change_log
--
-- audit_logs: schema free-form theo @Auditable (action/entity_type/entity_id,
-- old_value/new_value JSONB, ip) — KHÔNG có CHECK trên action.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    customer_id BIGINT,
    supplier_id BIGINT,
    amount NUMERIC(15,2) NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    note TEXT,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_payments_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_payments_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_payments_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT fk_payments_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_payments_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE audit_logs (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       REFERENCES users(id),
    business_id BIGINT       REFERENCES businesses(id),
    store_id    BIGINT       REFERENCES stores(id),
    action      VARCHAR(50)  NOT NULL,
    entity_type VARCHAR(50)  NOT NULL,
    entity_id   BIGINT,
    old_value   JSONB,
    new_value   JSONB,
    ip          VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    -- Không có CHECK trên action: action là free-form theo @Auditable
    -- (CREATE_ORDER, ADJUST_INVENTORY, RECEIVE_PURCHASE_ORDER, ...)
);

CREATE TABLE sync_change_log (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    table_name VARCHAR(50) NOT NULL,
    record_public_id UUID NOT NULL,
    operation VARCHAR(10) NOT NULL,
    sync_version BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_by_device UUID,
    CONSTRAINT fk_sync_log_store FOREIGN KEY (store_id) REFERENCES stores(id)
);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_payments_customer_id ON payments(customer_id);
CREATE INDEX idx_payments_supplier_id ON payments(supplier_id);
CREATE INDEX idx_payments_store_created ON payments(store_id, created_at DESC);

CREATE INDEX idx_audit_logs_user_id     ON audit_logs(user_id, created_at DESC);
CREATE INDEX idx_audit_logs_store_id    ON audit_logs(store_id, created_at DESC);
CREATE INDEX idx_audit_logs_business_id ON audit_logs(business_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity      ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at  ON audit_logs(created_at DESC);

CREATE INDEX idx_sync_log_store_version ON sync_change_log(store_id, sync_version);

-- ----------------------------------------------------------------------------
-- CHECK constraints
-- ----------------------------------------------------------------------------

ALTER TABLE payments ADD CONSTRAINT chk_payments_amount CHECK (amount > 0);
ALTER TABLE payments ADD CONSTRAINT chk_payments_method CHECK (payment_method IN ('CASH', 'BANK_TRANSFER', 'CREDIT_CARD', 'DEBIT_CARD', 'MOBILE_PAYMENT', 'OTHER'));
ALTER TABLE payments ADD CONSTRAINT chk_payments_reference CHECK (customer_id IS NULL OR supplier_id IS NULL);

ALTER TABLE sync_change_log ADD CONSTRAINT chk_sync_log_operation CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE'));
