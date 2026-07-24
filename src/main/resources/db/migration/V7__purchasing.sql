-- ============================================================================
-- V7: Purchasing
-- purchase_orders, purchase_order_items
--
-- Nhập hàng thuộc scope store (chi nhánh).
-- Sau khi purchase_orders tồn tại, gắn FK inventory_transactions.purchase_order_id (đã defer từ V4).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

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
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_poi_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
    CONSTRAINT fk_poi_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_poi_store FOREIGN KEY (store_id) REFERENCES stores(id),
    CONSTRAINT fk_poi_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

-- ----------------------------------------------------------------------------
-- Deferred FK: inventory_transactions.purchase_order_id (purchase_orders giờ đã tồn tại)
-- ----------------------------------------------------------------------------

ALTER TABLE inventory_transactions
    ADD CONSTRAINT fk_inv_tx_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_purchase_orders_supplier_id ON purchase_orders(supplier_id);
CREATE INDEX idx_purchase_orders_warehouse_id ON purchase_orders(warehouse_id);
CREATE INDEX idx_po_store_status ON purchase_orders(store_id, status);
CREATE INDEX idx_purchase_order_items_po_id ON purchase_order_items(purchase_order_id);

-- ----------------------------------------------------------------------------
-- CHECK constraints
-- ----------------------------------------------------------------------------

ALTER TABLE purchase_orders ADD CONSTRAINT chk_po_amounts CHECK (total_amount >= 0 AND paid_amount >= 0 AND debt_amount >= 0);
ALTER TABLE purchase_orders ADD CONSTRAINT chk_po_status CHECK (status IN ('PENDING', 'RECEIVED', 'CANCELLED'));

ALTER TABLE purchase_order_items ADD CONSTRAINT chk_poi_quantity CHECK (quantity > 0);
