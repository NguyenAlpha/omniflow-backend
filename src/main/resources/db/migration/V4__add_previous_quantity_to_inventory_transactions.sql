ALTER TABLE inventory_transactions
    ADD COLUMN IF NOT EXISTS previous_quantity NUMERIC(15, 2);
