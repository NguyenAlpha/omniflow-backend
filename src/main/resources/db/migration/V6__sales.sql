-- ============================================================================
-- V6: Sales
-- orders, order_items, return_orders, return_order_items
--
-- Giao dịch bán hàng thuộc scope store (chi nhánh).
-- orders.refunded_amount: phần đã hoàn qua return order COMPLETED (trừ khỏi doanh thu thuần ở V9).
-- Sau khi orders tồn tại, gắn FK inventory_transactions.order_id (đã defer từ V4).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

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
    refunded_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
    payment_method VARCHAR(20) NOT NULL DEFAULT 'CASH',
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
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_return_order_items_ro FOREIGN KEY (return_order_id) REFERENCES return_orders(id),
    CONSTRAINT fk_return_order_items_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_return_order_items_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_return_order_items_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

-- ----------------------------------------------------------------------------
-- Deferred FK: inventory_transactions.order_id (orders giờ đã tồn tại)
-- ----------------------------------------------------------------------------

ALTER TABLE inventory_transactions
    ADD CONSTRAINT fk_inv_tx_order FOREIGN KEY (order_id) REFERENCES orders(id);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_warehouse_id ON orders(warehouse_id);
CREATE INDEX idx_orders_store_status ON orders(store_id, status);
CREATE INDEX idx_orders_store_created ON orders(store_id, created_at DESC);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);
CREATE INDEX idx_order_items_store_id ON order_items(store_id);
CREATE INDEX idx_return_orders_store_id ON return_orders(store_id);
CREATE INDEX idx_return_orders_original_order ON return_orders(original_order_id);
CREATE INDEX idx_return_order_items_ro_id ON return_order_items(return_order_id);
CREATE INDEX idx_return_order_items_store_id ON return_order_items(store_id);
CREATE INDEX idx_return_order_items_product_id ON return_order_items(product_id);

-- ----------------------------------------------------------------------------
-- CHECK constraints
-- ----------------------------------------------------------------------------

ALTER TABLE orders ADD CONSTRAINT chk_orders_amounts CHECK (subtotal >= 0 AND discount >= 0 AND tax >= 0 AND total_amount >= 0 AND paid_amount >= 0 AND debt_amount >= 0);
ALTER TABLE orders ADD CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'));
ALTER TABLE orders ADD CONSTRAINT chk_orders_discount_type CHECK (discount_type IN ('FIXED', 'PERCENT'));

ALTER TABLE order_items ADD CONSTRAINT chk_order_items_quantity CHECK (quantity > 0);
ALTER TABLE order_items ADD CONSTRAINT chk_order_items_discount_type CHECK (discount_type IN ('FIXED', 'PERCENT'));

ALTER TABLE return_orders ADD CONSTRAINT chk_return_orders_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED'));
ALTER TABLE return_orders ADD CONSTRAINT chk_return_orders_refund CHECK (total_refund >= 0);
ALTER TABLE return_orders ADD CONSTRAINT chk_return_orders_method CHECK (refund_method IN ('CASH', 'BANK_TRANSFER', 'STORE_CREDIT'));

ALTER TABLE return_order_items ADD CONSTRAINT chk_return_order_items_quantity CHECK (quantity > 0);
