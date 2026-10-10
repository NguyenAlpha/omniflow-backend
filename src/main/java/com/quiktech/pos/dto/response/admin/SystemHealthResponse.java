package com.quiktech.pos.dto.response.admin;

import java.time.Instant;
import java.util.Map;

/**
 * Tình trạng tức thời của instance API đang trả lời request ({@code GET /api/admin/traffic/system}).
 * Giá trị lấy từ Micrometer/Actuator lúc gọi, không có lịch sử. Trường nào không đo được là {@code null}.
 *
 * @param components trạng thái từng thành phần của health check (VD {@code db}, {@code redis}) → {@code UP}/{@code DOWN}
 * @param cpuUsage   tỉ lệ 0..1
 */
public record SystemHealthResponse(
        Instant checkedAt,
        String status,
        Map<String, String> components,
        Long uptimeSeconds,
        Long heapUsedBytes,
        Long heapMaxBytes,
        Double processCpuUsage,
        Double systemCpuUsage,
        Integer liveThreads,
        Integer dbConnectionsActive,
        Integer dbConnectionsIdle,
        Integer dbConnectionsMax,
        Integer dbConnectionsPending
) {
}
