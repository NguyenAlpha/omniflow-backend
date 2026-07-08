DROP TABLE IF EXISTS audit_logs CASCADE;

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

CREATE INDEX idx_audit_logs_user_id     ON audit_logs(user_id, created_at DESC);
CREATE INDEX idx_audit_logs_store_id    ON audit_logs(store_id, created_at DESC);
CREATE INDEX idx_audit_logs_business_id ON audit_logs(business_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity      ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at  ON audit_logs(created_at DESC);
