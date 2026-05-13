-- Flyway Migration V1: OmniFlow Initial Schema
-- Date: 2026-05-07
-- 24 Tables with local-first sync support, full-text search, and multi-tenant isolation

-- ============================================================================
-- Section 1: Users & Permissions & Stores
-- ============================================================================

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
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
    ('ROLE_OWNER',       'Chủ cửa hàng — toàn quyền trong cửa hàng'),
    ('ROLE_MANAGER',     'Quản lý cửa hàng'),
    ('ROLE_STAFF',       'Nhân viên cửa hàng');

CREATE TABLE stores (
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
    store_id BIGINT,
    granted_by BIGINT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id),
    CONSTRAINT fk_user_roles_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_user_roles_granted_by FOREIGN KEY (granted_by) REFERENCES users(id)
);

CREATE TABLE subscriptions (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL UNIQUE,
    plan VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    billing_cycle VARCHAR(20),
    max_staff INTEGER,
    max_products INTEGER,
    max_warehouses INTEGER,
    max_orders_per_month INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_subscriptions_store FOREIGN KEY (store_id) REFERENCES stores(id)
);

-- ============================================================================
-- Section 2: Categories & Units
-- ============================================================================

CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_categories_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_categories_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_categories_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE units (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT,
    name VARCHAR(50) NOT NULL,
    abbreviation VARCHAR(10) NOT NULL,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_units_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_units_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

-- ============================================================================
-- Section 3: Products
-- ============================================================================

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    category_id BIGINT,
    unit_id BIGINT NOT NULL,
    cost_price NUMERIC(15,2) NOT NULL,
    selling_price NUMERIC(15,2) NOT NULL,
    min_stock_level INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    search_vector TSVECTOR,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_products_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id),
    CONSTRAINT fk_products_unit FOREIGN KEY (unit_id) REFERENCES units(id),
    CONSTRAINT fk_products_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

CREATE TABLE price_history (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    old_cost_price NUMERIC(15,2) NOT NULL,
    new_cost_price NUMERIC(15,2) NOT NULL,
    old_selling_price NUMERIC(15,2) NOT NULL,
    new_selling_price NUMERIC(15,2) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_price_history_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_price_history_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_price_history_changed_by FOREIGN KEY (changed_by) REFERENCES users(id)
);

-- ============================================================================
-- Section 4: Warehouses & Inventory
-- ============================================================================

CREATE TABLE warehouses (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    address TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_warehouses_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_warehouses_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

CREATE TABLE inventory (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    quantity NUMERIC(15,2) NOT NULL DEFAULT 0,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_inventory_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
    CONSTRAINT fk_inventory_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_inventory_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT ux_inventory_product_warehouse UNIQUE (product_id, warehouse_id)
);

CREATE TABLE inventory_transactions (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    quantity NUMERIC(15,2) NOT NULL,
    order_id BIGINT,
    purchase_order_id BIGINT,
    note TEXT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_inv_tx_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_inv_tx_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_inv_tx_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
    CONSTRAINT fk_inv_tx_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

-- ============================================================================
-- Section 5: Customers & Suppliers
-- ============================================================================

CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(20),
    email VARCHAR(100),
    address TEXT,
    debt_balance NUMERIC(15,2) NOT NULL DEFAULT 0,
    search_vector TSVECTOR,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_customers_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_customers_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_customers_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE suppliers (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL,
    phone VARCHAR(20),
    email VARCHAR(100),
    address TEXT,
    debt_balance NUMERIC(15,2) NOT NULL DEFAULT 0,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_suppliers_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_suppliers_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_suppliers_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

-- ============================================================================
-- Section 6: Orders & Return Orders
-- ============================================================================

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    order_code VARCHAR(20) NOT NULL,
    customer_id BIGINT,
    warehouse_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    subtotal NUMERIC(15,2) NOT NULL,
    discount NUMERIC(15,2) NOT NULL DEFAULT 0,
    discount_type VARCHAR(10) NOT NULL DEFAULT 'FIXED',
    tax NUMERIC(15,2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(15,2) NOT NULL,
    paid_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
    debt_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
    note TEXT,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_orders_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_orders_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
    CONSTRAINT fk_orders_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_orders_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT ux_orders_store_code UNIQUE (store_id, order_code)
);

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    quantity NUMERIC(15,2) NOT NULL,
    unit_price NUMERIC(15,2) NOT NULL,
    discount NUMERIC(15,2) NOT NULL DEFAULT 0,
    discount_type VARCHAR(10) NOT NULL DEFAULT 'FIXED',
    total_price NUMERIC(15,2) NOT NULL,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_order_items_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_order_items_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

CREATE TABLE return_orders (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    return_code VARCHAR(20) NOT NULL,
    original_order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason TEXT NOT NULL,
    total_refund NUMERIC(15,2) NOT NULL,
    refund_method VARCHAR(20) NOT NULL,
    note TEXT,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_return_orders_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_return_orders_original_order FOREIGN KEY (original_order_id) REFERENCES orders(id),
    CONSTRAINT fk_return_orders_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
    CONSTRAINT fk_return_orders_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_return_orders_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT ux_return_orders_store_code UNIQUE (store_id, return_code)
);

CREATE TABLE return_order_items (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    return_order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity NUMERIC(15,2) NOT NULL,
    unit_price NUMERIC(15,2) NOT NULL,
    total_refund NUMERIC(15,2) NOT NULL,
    CONSTRAINT fk_return_order_items_ro FOREIGN KEY (return_order_id) REFERENCES return_orders(id),
    CONSTRAINT fk_return_order_items_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_return_order_items_store FOREIGN KEY (store_id) REFERENCES stores(id)
);

-- ============================================================================
-- Section 7: Purchase Orders
-- ============================================================================

CREATE TABLE purchase_orders (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    order_code VARCHAR(20) NOT NULL,
    supplier_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_amount NUMERIC(15,2) NOT NULL,
    paid_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
    debt_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
    note TEXT,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_po_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT fk_po_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
    CONSTRAINT fk_po_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_po_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT ux_po_store_code UNIQUE (store_id, order_code)
);

CREATE TABLE purchase_order_items (
    id BIGSERIAL PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    quantity NUMERIC(15,2) NOT NULL,
    unit_price NUMERIC(15,2) NOT NULL,
    total_price NUMERIC(15,2) NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_poi_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
    CONSTRAINT fk_poi_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_poi_store FOREIGN KEY (store_id) REFERENCES stores(id)
);

-- ============================================================================
-- Section 8: Payments & Audit & Sync
-- ============================================================================

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
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT,
    performed_by BIGINT NOT NULL,
    table_name VARCHAR(50) NOT NULL,
    record_id BIGINT NOT NULL,
    action VARCHAR(10) NOT NULL,
    old_data JSONB,
    new_data JSONB,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_audit_logs_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_audit_logs_performed_by FOREIGN KEY (performed_by) REFERENCES users(id)
);

CREATE TABLE subscription_invoices (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    plan VARCHAR(20) NOT NULL,
    billing_cycle VARCHAR(20) NOT NULL,
    amount NUMERIC(15,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_method VARCHAR(20),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_sub_invoices_store FOREIGN KEY (store_id) REFERENCES stores(id)
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

-- ============================================================================
-- Section 8b: Deferred FK constraints (tables created after inventory_transactions)
-- ============================================================================

ALTER TABLE inventory_transactions
    ADD CONSTRAINT fk_inv_tx_order FOREIGN KEY (order_id) REFERENCES orders(id),
    ADD CONSTRAINT fk_inv_tx_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id);

-- ============================================================================
-- Section 9: Indexes - Foreign Keys
-- ============================================================================

CREATE INDEX idx_store_members_user_id ON store_members(user_id);
CREATE INDEX idx_store_members_store_id ON store_members(store_id);
CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles(role_id);
CREATE INDEX idx_user_roles_store_id ON user_roles(store_id) WHERE store_id IS NOT NULL;
CREATE INDEX idx_categories_store_id ON categories(store_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_store_id ON products(store_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_category_id ON products(category_id);
CREATE INDEX idx_warehouses_store_id ON warehouses(store_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_inventory_warehouse_id ON inventory(warehouse_id);
CREATE INDEX idx_inventory_product_id ON inventory(product_id);
CREATE INDEX idx_customers_store_id ON customers(store_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_suppliers_store_id ON suppliers(store_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_warehouse_id ON orders(warehouse_id);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);
CREATE INDEX idx_purchase_orders_supplier_id ON purchase_orders(supplier_id);
CREATE INDEX idx_purchase_order_items_po_id ON purchase_order_items(purchase_order_id);
CREATE INDEX idx_payments_customer_id ON payments(customer_id);
CREATE INDEX idx_payments_supplier_id ON payments(supplier_id);
CREATE INDEX idx_inventory_tx_product_id ON inventory_transactions(product_id);

-- ============================================================================
-- Section 10: Indexes - Composite & Business Logic
-- ============================================================================

CREATE INDEX idx_orders_store_status ON orders(store_id, status);
CREATE INDEX idx_orders_store_created ON orders(store_id, created_at DESC);
CREATE INDEX idx_inv_tx_store_created ON inventory_transactions(store_id, created_at DESC);
CREATE INDEX idx_po_store_status ON purchase_orders(store_id, status);
CREATE INDEX idx_inventory_product_warehouse ON inventory(product_id, warehouse_id);
CREATE INDEX idx_inv_tx_order_id ON inventory_transactions(order_id);
CREATE INDEX idx_inv_tx_po_id ON inventory_transactions(purchase_order_id);
CREATE INDEX idx_inventory_product_qty ON inventory(product_id, quantity);
CREATE INDEX idx_customers_store_debt ON customers(store_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0;
CREATE INDEX idx_suppliers_store_debt ON suppliers(store_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0;
CREATE INDEX idx_products_store_active ON products(store_id, is_active) WHERE deleted_at IS NULL;
CREATE INDEX idx_warehouses_store_active ON warehouses(store_id, is_active) WHERE deleted_at IS NULL;
CREATE INDEX idx_return_orders_store_id ON return_orders(store_id);
CREATE INDEX idx_return_orders_original_order ON return_orders(original_order_id);
CREATE INDEX idx_return_order_items_ro_id ON return_order_items(return_order_id);
CREATE INDEX idx_return_order_items_store_id ON return_order_items(store_id);
CREATE INDEX idx_price_history_product_id ON price_history(product_id, changed_at DESC);
CREATE INDEX idx_price_history_store_created ON price_history(store_id, changed_at DESC);
CREATE INDEX idx_units_store_id ON units(store_id) WHERE store_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_audit_logs_store_id ON audit_logs(store_id, created_at DESC);
CREATE INDEX idx_audit_logs_table_record ON audit_logs(table_name, record_id);
CREATE INDEX idx_audit_logs_performed_by ON audit_logs(performed_by, created_at DESC);
CREATE INDEX idx_sub_invoices_store_id ON subscription_invoices(store_id, created_at DESC);
CREATE INDEX idx_sub_invoices_status ON subscription_invoices(store_id, status) WHERE status = 'PENDING';

-- Partial UNIQUE indexes for soft-delete semantics
CREATE UNIQUE INDEX ux_store_members_user_store ON store_members(user_id, store_id) WHERE deleted_at IS NULL;
-- COALESCE(store_id, 0): treat NULL store_id as 0 so global roles are also unique per (user, role)
CREATE UNIQUE INDEX ux_user_roles ON user_roles(user_id, role_id, COALESCE(store_id, 0)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_categories_store_name ON categories(store_id, name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_units_store_name ON units(COALESCE(store_id, 0), name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_products_store_sku ON products(store_id, sku) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_customers_store_code ON customers(store_id, code) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_suppliers_store_code ON suppliers(store_id, code) WHERE deleted_at IS NULL;

-- ============================================================================
-- Section 11: Full-Text Search Indexes (GIN)
-- ============================================================================

CREATE INDEX idx_products_search_vector ON products USING GIN(search_vector);
CREATE INDEX idx_customers_search_vector ON customers USING GIN(search_vector);

-- ============================================================================
-- Section 12: CHECK Constraints
-- ============================================================================

ALTER TABLE users ADD CONSTRAINT chk_users_email_format CHECK (email ~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}$');

ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_plan CHECK (plan IN ('FREE', 'BASIC', 'PRO'));
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED'));
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_billing_cycle CHECK (billing_cycle IN ('MONTHLY', 'YEARLY'));
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_period CHECK (expires_at IS NULL OR expires_at > started_at);

ALTER TABLE products ADD CONSTRAINT chk_products_price CHECK (cost_price >= 0 AND selling_price >= 0);
ALTER TABLE products ADD CONSTRAINT chk_products_min_stock CHECK (min_stock_level >= 0);

ALTER TABLE orders ADD CONSTRAINT chk_orders_amounts CHECK (subtotal >= 0 AND discount >= 0 AND tax >= 0 AND total_amount >= 0 AND paid_amount >= 0 AND debt_amount >= 0);
ALTER TABLE orders ADD CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'));
ALTER TABLE orders ADD CONSTRAINT chk_orders_discount_type CHECK (discount_type IN ('FIXED', 'PERCENT'));

ALTER TABLE order_items ADD CONSTRAINT chk_order_items_quantity CHECK (quantity > 0);
ALTER TABLE order_items ADD CONSTRAINT chk_order_items_discount_type CHECK (discount_type IN ('FIXED', 'PERCENT'));

ALTER TABLE return_orders ADD CONSTRAINT chk_return_orders_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'));
ALTER TABLE return_orders ADD CONSTRAINT chk_return_orders_refund CHECK (total_refund >= 0);
ALTER TABLE return_orders ADD CONSTRAINT chk_return_orders_method CHECK (refund_method IN ('CASH', 'BANK_TRANSFER', 'STORE_CREDIT'));

ALTER TABLE return_order_items ADD CONSTRAINT chk_return_order_items_quantity CHECK (quantity > 0);

ALTER TABLE purchase_orders ADD CONSTRAINT chk_po_amounts CHECK (total_amount >= 0 AND paid_amount >= 0 AND debt_amount >= 0);
ALTER TABLE purchase_orders ADD CONSTRAINT chk_po_status CHECK (status IN ('PENDING', 'RECEIVED', 'CANCELLED'));

ALTER TABLE purchase_order_items ADD CONSTRAINT chk_poi_quantity CHECK (quantity > 0);

ALTER TABLE payments ADD CONSTRAINT chk_payments_amount CHECK (amount > 0);
ALTER TABLE payments ADD CONSTRAINT chk_payments_method CHECK (payment_method IN ('CASH', 'BANK_TRANSFER'));
ALTER TABLE payments ADD CONSTRAINT chk_payments_reference CHECK ((customer_id IS NOT NULL) != (supplier_id IS NOT NULL));

ALTER TABLE inventory ADD CONSTRAINT chk_inventory_qty CHECK (quantity >= 0);

ALTER TABLE inventory_transactions ADD CONSTRAINT chk_inv_tx_type CHECK (type IN ('IN', 'OUT', 'TRANSFER', 'ADJUSTMENT'));
ALTER TABLE inventory_transactions ADD CONSTRAINT chk_inv_tx_qty CHECK (quantity > 0);

ALTER TABLE audit_logs ADD CONSTRAINT chk_audit_logs_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE'));

ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_status CHECK (status IN ('PAID', 'PENDING', 'FAILED'));
ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_amount CHECK (amount >= 0);
ALTER TABLE subscription_invoices ADD CONSTRAINT chk_sub_invoices_period CHECK (period_end > period_start);

ALTER TABLE sync_change_log ADD CONSTRAINT chk_sync_log_operation CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE'));

-- ============================================================================
-- Section 13: Materialized Views
-- ============================================================================

CREATE MATERIALIZED VIEW mv_monthly_revenue AS
SELECT store_id,
       DATE_TRUNC('month', created_at)::DATE AS month,
       SUM(total_amount) AS revenue,
       SUM(paid_amount) AS collected,
       SUM(debt_amount) AS uncollected,
       COUNT(*) AS order_count
FROM orders
WHERE status = 'COMPLETED'
GROUP BY store_id, DATE_TRUNC('month', created_at);

CREATE UNIQUE INDEX ON mv_monthly_revenue(store_id, month);

CREATE MATERIALIZED VIEW mv_inventory_summary AS
SELECT p.store_id,
       p.id AS product_id,
       p.name AS product_name,
       p.sku,
       p.min_stock_level,
       SUM(i.quantity) AS total_quantity,
       CASE WHEN SUM(i.quantity) < p.min_stock_level THEN true ELSE false END AS is_low_stock
FROM products p
JOIN inventory i ON i.product_id = p.id
WHERE p.deleted_at IS NULL
GROUP BY p.store_id, p.id, p.name, p.sku, p.min_stock_level;

CREATE UNIQUE INDEX ON mv_inventory_summary(store_id, product_id);
CREATE INDEX ON mv_inventory_summary(store_id, is_low_stock) WHERE is_low_stock = true;

-- ============================================================================
-- Section 14: Triggers for Full-Text Search (tsvector)
-- ============================================================================

CREATE FUNCTION products_tsvector_trigger() RETURNS trigger AS $$
BEGIN
  new.search_vector := to_tsvector('simple',
    COALESCE(new.name, '') || ' ' ||
    COALESCE(new.sku, '') || ' ' ||
    COALESCE(new.description, '')
  );
  RETURN new;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tsvector_update_products BEFORE INSERT OR UPDATE ON products
FOR EACH ROW EXECUTE FUNCTION products_tsvector_trigger();

CREATE FUNCTION customers_tsvector_trigger() RETURNS trigger AS $$
BEGIN
  new.search_vector := to_tsvector('simple',
    COALESCE(new.name, '') || ' ' ||
    COALESCE(new.code, '') || ' ' ||
    COALESCE(new.phone, '') || ' ' ||
    COALESCE(new.email, '')
  );
  RETURN new;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tsvector_update_customers BEFORE INSERT OR UPDATE ON customers
FOR EACH ROW EXECUTE FUNCTION customers_tsvector_trigger();

-- ============================================================================
-- End of V1__initial_schema.sql
-- ============================================================================

