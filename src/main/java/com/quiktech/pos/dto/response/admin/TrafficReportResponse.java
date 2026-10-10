package com.quiktech.pos.dto.response.admin;

import java.time.Instant;
import java.util.List;

/**
 * Báo cáo lưu lượng API cho {@code GET /api/admin/traffic}. Thời gian xử lý tính bằng ms;
 * p50/p95/p99 là ước lượng (mốc trên của khoảng đếm chứa phân vị), {@code null} khi không có
 * request hoặc phân vị rơi vào khoảng trên 5000 ms.
 */
public record TrafficReportResponse(
        String range,
        Instant from,
        Instant to,
        long bucketSeconds,
        Summary summary,
        List<Point> series,
        List<StatusCount> statuses,
        List<Endpoint> endpoints,
        List<BusinessUsage> businesses
) {
    /**
     * @param serverErrors 5xx
     * @param clientErrors 4xx (gồm cả 401/403/429)
     * @param rateLimited  429
     */
    public record Summary(long requests, double requestsPerMinute, long serverErrors, long clientErrors,
                          long rateLimited, Double avgMs, Long p50Ms, Long p95Ms, Long p99Ms, long maxMs) {}

    public record Point(Instant time, long requests, long serverErrors, long clientErrors, Double avgMs, Long p95Ms) {}

    public record StatusCount(int status, long requests) {}

    public record Endpoint(String method, String route, long requests, long serverErrors, long clientErrors,
                           Double avgMs, Long p95Ms, long maxMs) {}

    public record BusinessUsage(long businessId, String businessName, long requests, long serverErrors, Double avgMs) {}
}
