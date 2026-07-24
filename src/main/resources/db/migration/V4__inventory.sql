-- ============================================================================
-- V4: Inventory
-- warehouses, inventory, inventory_transactions
--
-- warehouses thuộc store (chi nhánh). inventory theo (product, warehouse).
-- inventory_transactions ghi nhật ký nhập/xuất/chuyển/điều chỉnh.
-- FK order_id/purchase_order_id được thêm sau (V6/V7) vì orders/purchase_orders
-- chưa tồn tại ở bước này; index cho 2 cột đó vẫn tạo được ngay tại đây.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

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
    CONSTRAINT fk_inventory_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

CREATE TABLE inventory_transactions (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    quantity NUMERIC(15,2) NOT NULL,
    previous_quantity NUMERIC(15,2),
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

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_warehouses_store_id ON warehouses(store_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_warehouses_store_active ON warehouses(store_id, is_active) WHERE deleted_at IS NULL;
CREATE INDEX idx_inventory_warehouse_id ON inventory(warehouse_id);
CREATE INDEX idx_inventory_product_id ON inventory(product_id);
CREATE INDEX idx_inventory_product_warehouse ON inventory(product_id, warehouse_id);
CREATE INDEX idx_inventory_product_qty ON inventory(product_id, quantity);
CREATE INDEX idx_inventory_store_id ON inventory(store_id);
CREATE INDEX idx_inventory_tx_product_id ON inventory_transactions(product_id);
CREATE INDEX idx_inv_tx_store_created ON inventory_transactions(store_id, created_at DESC);
CREATE INDEX idx_inventory_tx_warehouse_id ON inventory_transactions(warehouse_id);
CREATE INDEX idx_inv_tx_order_id ON inventory_transactions(order_id);
CREATE INDEX idx_inv_tx_po_id ON inventory_transactions(purchase_order_id);

-- Partial UNIQUE index cho soft-delete
CREATE UNIQUE INDEX ux_inventory_product_warehouse ON inventory(product_id, warehouse_id) WHERE deleted_at IS NULL;

-- ----------------------------------------------------------------------------
-- CHECK constraints
-- ----------------------------------------------------------------------------

ALTER TABLE inventory ADD CONSTRAINT chk_inventory_qty CHECK (quantity >= 0);

ALTER TABLE inventory_transactions ADD CONSTRAINT chk_inv_tx_type CHECK (type IN ('IN', 'OUT', 'TRANSFER', 'ADJUSTMENT'));
-- quantity <> 0 (không phải > 0): TRANSFER/ADJUSTMENT ghi delta có dấu
-- (chân xuất của transfer và điều chỉnh giảm là số âm); IN/OUT ghi số dương.
ALTER TABLE inventory_transactions ADD CONSTRAINT chk_inv_tx_qty CHECK (quantity <> 0);
