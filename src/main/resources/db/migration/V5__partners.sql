-- ============================================================================
-- V5: Partners
-- customers, suppliers
--
-- Partner thuộc scope business. customers có full-text search (search_vector).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Tables
-- ----------------------------------------------------------------------------

CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL,
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
    CONSTRAINT fk_customers_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_customers_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_customers_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

CREATE TABLE suppliers (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL,
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
    CONSTRAINT fk_suppliers_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_suppliers_modified_by FOREIGN KEY (last_modified_by_user) REFERENCES users(id),
    CONSTRAINT fk_suppliers_created_by FOREIGN KEY (created_by) REFERENCES users(id)
);

-- ----------------------------------------------------------------------------
-- Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_customers_business_id ON customers(business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_suppliers_business_id ON suppliers(business_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_customers_business_debt ON customers(business_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0;
CREATE INDEX idx_suppliers_business_debt ON suppliers(business_id, debt_balance DESC) WHERE deleted_at IS NULL AND debt_balance > 0;

-- Partial UNIQUE indexes cho soft-delete
CREATE UNIQUE INDEX ux_customers_business_code ON customers(business_id, code) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_suppliers_business_code ON suppliers(business_id, code) WHERE deleted_at IS NULL;

-- Full-text search (GIN)
CREATE INDEX idx_customers_search_vector ON customers USING GIN(search_vector);

-- ----------------------------------------------------------------------------
-- Full-text search trigger (tsvector)
-- ----------------------------------------------------------------------------

CREATE FUNCTION customers_tsvector_trigger() RETURNS trigger AS $$
BEGIN
  new.search_vector := to_tsvector('simple', unaccent(
    COALESCE(new.name, '') || ' ' ||
    COALESCE(new.code, '') || ' ' ||
    COALESCE(new.phone, '') || ' ' ||
    COALESCE(new.email, '')
  ));
  RETURN new;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER tsvector_update_customers BEFORE INSERT OR UPDATE ON customers
FOR EACH ROW EXECUTE FUNCTION customers_tsvector_trigger();
