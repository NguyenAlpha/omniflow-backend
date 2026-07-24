-- ============================================================================
-- V3: Catalog
-- categories, units, products, price_history
--
-- Catalog thuộc scope business (dùng chung cho mọi chi nhánh của tenant).
-- products có full-text search (search_vector + trigger unaccent).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL,
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
    CONSTRAINT fk_categories_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_categories_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_categories_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE units (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT,  -- NULL = system unit (dùng chung toàn hệ thống)
    name VARCHAR(50) NOT NULL,
    abbreviation VARCHAR(10) NOT NULL,
    public_id UUID NOT NULL UNIQUE,
    sync_version BIGINT NOT NULL DEFAULT 0,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_by_user BIGINT,
    last_modified_by_device UUID,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT fk_units_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_units_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    category_id BIGINT,
    unit_id BIGINT NOT NULL,
    cost_price NUMERIC(15,2) NOT NULL,
    selling_price NUMERIC(15,2) NOT NULL,
    min_stock_level INTEGER NOT NULL DEFAULT 0,
    total_stock NUMERIC(15,2) NOT NULL DEFAULT 0,
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
    CONSTRAINT fk_products_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id),
    CONSTRAINT fk_products_unit FOREIGN KEY (unit_id) REFERENCES units(id),
    CONSTRAINT fk_products_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id)
);

CREATE TABLE price_history (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    old_cost_price NUMERIC(15,2) NOT NULL,
    new_cost_price NUMERIC(15,2) NOT NULL,
    old_selling_price NUMERIC(15,2) NOT NULL,
    new_selling_price NUMERIC(15,2) NOT NULL,
    changed_by BIGINT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_price_history_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_price_history_product FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_price_history_changed_by FOREIGN KEY (changed_by) REFERENCES users(id)
);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_categories_business_id ON categories(business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_units_business_id ON units(business_id) WHERE business_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_products_business_id ON products(business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_category_id ON products(category_id);
CREATE INDEX idx_products_business_active ON products(business_id, is_active) WHERE deleted_at IS NULL;
CREATE INDEX idx_price_history_product_id ON price_history(product_id, changed_at DESC);
CREATE INDEX idx_price_history_business_created ON price_history(business_id, changed_at DESC);

-- Partial UNIQUE indexes cho soft-delete
CREATE UNIQUE INDEX ux_categories_business_name ON categories(business_id, name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_units_business_name ON units(COALESCE(business_id, 0), name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_products_business_sku ON products(business_id, sku) WHERE deleted_at IS NULL;

-- Full-text search (GIN)
CREATE INDEX idx_products_search_vector ON products USING GIN(search_vector);

-- ----------------------------------------------------------------------------
-- CHECK constraints
-- ----------------------------------------------------------------------------

ALTER TABLE products ADD CONSTRAINT chk_products_price CHECK (cost_price >= 0 AND selling_price >= 0);
ALTER TABLE products ADD CONSTRAINT chk_products_min_stock CHECK (min_stock_level >= 0);

-- ----------------------------------------------------------------------------
-- Full-text search trigger (tsvector)
-- ----------------------------------------------------------------------------

CREATE FUNCTION products_tsvector_trigger() RETURNS trigger AS $$
BEGIN
  new.search_vector := to_tsvector('simple', unaccent(
    COALESCE(new.name, '') || ' ' ||
    COALESCE(new.sku, '') || ' ' ||
    COALESCE(new.description, '')
  ));
  RETURN new;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tsvector_update_products BEFORE INSERT OR UPDATE ON products
FOR EACH ROW EXECUTE FUNCTION products_tsvector_trigger();
