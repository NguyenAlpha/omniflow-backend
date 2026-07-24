-- Doanh thu thuần phải trừ phần đã hoàn qua đơn trả (return order) đã COMPLETED.
-- Trước đây mv_monthly_revenue = SUM(total_amount) nên hàng trả vẫn tính đủ doanh thu.
ALTER TABLE orders ADD COLUMN refunded_amount NUMERIC(15,2) NOT NULL DEFAULT 0;

-- Không ALTER được câu SELECT của materialized view → drop và tạo lại (kèm index).
DROP MATERIALIZED VIEW mv_monthly_revenue;

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
