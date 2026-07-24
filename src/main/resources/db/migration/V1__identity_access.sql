-- ============================================================================
-- V1: Identity & Access
-- users, roles, businesses, stores, memberships, user_roles, refresh_tokens
--
-- Baseline gộp: schema cuối cùng đã bao gồm partial unique index cho username/email
-- (thay UNIQUE constraint), ux_user_roles_user_store, và bảng refresh_tokens.
-- Redesign tenancy: businesses (tenant) → stores (chi nhánh) → warehouses.
-- ============================================================================

-- Bỏ dấu tiếng Việt cho full-text search (dùng trong trigger tsvector + query ở V3/V5)
CREATE EXTENSION IF NOT EXISTS unaccent;

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    -- username/email KHÔNG dùng UNIQUE constraint: đã xóa mềm thì phải giải phóng
    -- định danh cho người mới. Dùng partial unique index (deleted_at IS NULL) bên dưới.
    username VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(200) NOT NULL,
    phone VARCHAR(20),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO roles (name, description) VALUES
    ('ROLE_SUPER_ADMIN', 'Quản trị viên hệ thống — toàn quyền'),
    ('ROLE_SUPPORT',     'Nhân viên hỗ trợ hệ thống'),
    ('ROLE_OWNER',       'Chủ doanh nghiệp — toàn quyền trên toàn bộ chi nhánh'),
    ('ROLE_MANAGER',     'Quản lý chi nhánh'),
    ('ROLE_STAFF',       'Nhân viên chi nhánh'),
    ('ROLE_BUSINESS_MANAGER', 'Trợ lý cấp doanh nghiệp — quản lý mọi chi nhánh, không đụng billing/hồ sơ doanh nghiệp');

CREATE TABLE businesses (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    address TEXT,
    phone VARCHAR(20),
    email VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE stores (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    address TEXT,
    phone VARCHAR(20),
    email VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_stores_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE TABLE business_members (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    business_id BIGINT NOT NULL,
    joined_date DATE,
    is_active BOOLEAN NOT NULL DEFAULT true,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_business_members_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_business_members_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_business_members_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

CREATE TABLE store_members (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    position_title VARCHAR(100),
    joined_date DATE,
    is_active BOOLEAN NOT NULL DEFAULT true,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_store_members_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_store_members_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_store_members_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

CREATE TABLE user_roles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    business_id BIGINT,  -- NOT NULL cho OWNER; NULL cho STAFF/MANAGER/global
    store_id BIGINT,     -- NOT NULL cho MANAGER/STAFF; NULL cho OWNER/global
    granted_by BIGINT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id),
    CONSTRAINT fk_user_roles_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_user_roles_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_user_roles_granted_by FOREIGN KEY (granted_by) REFERENCES users(id),
    CONSTRAINT chk_user_roles_scope CHECK (NOT (business_id IS NOT NULL AND store_id IS NOT NULL))
);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    token       VARCHAR(64)  NOT NULL UNIQUE,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_stores_business_id ON stores(business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_business_members_user_id ON business_members(user_id);
CREATE INDEX idx_business_members_business_id ON business_members(business_id);
CREATE INDEX idx_store_members_user_id ON store_members(user_id);
CREATE INDEX idx_store_members_store_id ON store_members(store_id);
CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles(role_id);
CREATE INDEX idx_user_roles_business_id ON user_roles(business_id) WHERE business_id IS NOT NULL;
CREATE INDEX idx_user_roles_store_id ON user_roles(store_id) WHERE store_id IS NOT NULL;
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- Partial UNIQUE indexes cho soft-delete: chỉ ràng buộc trên row đang sống.
CREATE UNIQUE INDEX uq_users_username_active ON users (username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_email_active ON users (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_business_members_user_business ON business_members(user_id, business_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_store_members_user_store ON store_members(user_id, store_id) WHERE deleted_at IS NULL;
-- COALESCE: treat NULL as 0 so global/business/branch roles are each unique per (user, role, scope)
CREATE UNIQUE INDEX ux_user_roles ON user_roles(user_id, role_id, COALESCE(business_id, 0), COALESCE(store_id, 0)) WHERE deleted_at IS NULL;
-- Backstop "mỗi user tối đa 1 role trong 1 store": ux_user_roles ở trên có role_id nên vẫn
-- cho phép MANAGER + STAFF cùng store → findActiveStoreRole trả 2 rows → 500. Index này chặn.
CREATE UNIQUE INDEX ux_user_roles_user_store ON user_roles(user_id, store_id)
    WHERE store_id IS NOT NULL AND deleted_at IS NULL;

-- ----------------------------------------------------------------------------
-- CHECK constraints
-- ----------------------------------------------------------------------------

ALTER TABLE users ADD CONSTRAINT chk_users_email_format CHECK (email ~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}$');
