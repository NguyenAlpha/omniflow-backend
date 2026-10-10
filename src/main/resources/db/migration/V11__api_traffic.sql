-- ============================================================================
-- V11: Thống kê lưu lượng API cho dashboard admin (/admin/traffic)
--
-- ApiTrafficFilter đo từng request, ApiTrafficRecorder cộng dồn trong bộ nhớ rồi mỗi phút
-- (UPSERT cộng thêm — nhiều instance cùng ghi một bucket vẫn đúng) vào:
--   api_traffic_minutely         — theo phút, giữ 2 ngày (khung 1 giờ / 24 giờ)
--   api_traffic_hourly           — cùng cột, theo giờ, giữ 30 ngày (khung 7 / 30 ngày)
--   api_traffic_business_hourly  — theo business, theo giờ, giữ 30 ngày
-- route là mẫu route của Spring (VD /api/stores/{storeId}/orders), không phải URL thật,
-- để số dòng không tăng theo số ID. Request bị chặn trước khi tới controller (429 theo IP,
-- 401) không có mẫu route → route = '(unmatched)'.
--
-- Thời gian xử lý đếm theo khoảng không chồng nhau để ước lượng p50/p95/p99 khi đọc:
-- le_50 = (0, 50] ms, le_100 = (50, 100] ms, …, le_5000 = (2500, 5000] ms, gt_5000 = > 5000 ms.
-- ============================================================================

CREATE TABLE api_traffic_minutely (
    bucket_start TIMESTAMPTZ NOT NULL,
    method VARCHAR(10) NOT NULL,
    route VARCHAR(200) NOT NULL,
    status SMALLINT NOT NULL,
    request_count BIGINT NOT NULL,
    total_duration_ms BIGINT NOT NULL,
    max_duration_ms BIGINT NOT NULL,
    le_50 BIGINT NOT NULL,
    le_100 BIGINT NOT NULL,
    le_250 BIGINT NOT NULL,
    le_500 BIGINT NOT NULL,
    le_1000 BIGINT NOT NULL,
    le_2500 BIGINT NOT NULL,
    le_5000 BIGINT NOT NULL,
    gt_5000 BIGINT NOT NULL,
    PRIMARY KEY (bucket_start, method, route, status)
);

CREATE TABLE api_traffic_hourly (LIKE api_traffic_minutely INCLUDING ALL);

CREATE TABLE api_traffic_business_hourly (
    bucket_start TIMESTAMPTZ NOT NULL,
    business_id BIGINT NOT NULL,
    request_count BIGINT NOT NULL,
    error_count BIGINT NOT NULL,
    total_duration_ms BIGINT NOT NULL,
    PRIMARY KEY (bucket_start, business_id)
);
