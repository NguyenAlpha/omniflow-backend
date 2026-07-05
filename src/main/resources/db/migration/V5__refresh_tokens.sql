CREATE TABLE refresh_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    token       VARCHAR(64)  NOT NULL UNIQUE,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
