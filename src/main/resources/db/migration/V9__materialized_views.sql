-- ============================================================================
-- V9: Materialized Views
-- mv_monthly_revenue, mv_inventory_summary
--
-- Đặt cuối cùng vì phụ thuộc orders/stores/products/inventory.
-- mv_monthly_revenue: doanh thu thuần = SUM(total_amount - refunded_amount) —
-- trừ phần đã hoàn qua return order COMPLETED.
-- ============================================================================

CREATE MATERIALIZED VIEW mv_monthly_revenue AS
SELECT s.business_id,
       o.store_id,
       DATE_TRUNC('month', o.created_at)::DATE AS month,
       SUM(o.total_amount - o.refunded_amount) AS revenue,
       SUM(o.paid_amount) AS collected,
       SUM(o.debt_amount) AS uncollected,
       COUNT(*) AS order_count
FROM orders o
JOIN stores s ON s.id = o.store_id
WHERE o.status = 'COMPLETED'
GROUP BY s.business_id, o.store_id, DATE_TRUNC('month', o.created_at);

CREATE UNIQUE INDEX ON mv_monthly_revenue(store_id, month);
CREATE INDEX ON mv_monthly_revenue(business_id, month);

CREATE MATERIALIZED VIEW mv_inventory_summary AS
SELECT p.business_id,
       p.id AS product_id,
       p.name AS product_name,
       p.sku,
       p.min_stock_level,
       SUM(i.quantity) AS total_stock,
       CASE WHEN SUM(i.quantity) < p.min_stock_level THEN true ELSE false END AS is_low_stock
FROM products p
JOIN inventory i ON i.product_id = p.id
WHERE p.deleted_at IS NULL
GROUP BY p.business_id, p.id, p.name, p.sku, p.min_stock_level;

CREATE UNIQUE INDEX ON mv_inventory_summary(business_id, product_id);
CREATE INDEX ON mv_inventory_summary(business_id, is_low_stock) WHERE is_low_stock = true;
