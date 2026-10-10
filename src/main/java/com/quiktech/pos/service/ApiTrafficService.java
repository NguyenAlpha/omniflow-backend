package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.admin.SystemHealthResponse;
import com.quiktech.pos.dto.response.admin.TrafficReportResponse;
import com.quiktech.pos.dto.response.admin.TrafficReportResponse.BusinessUsage;
import com.quiktech.pos.dto.response.admin.TrafficReportResponse.Endpoint;
import com.quiktech.pos.dto.response.admin.TrafficReportResponse.Point;
import com.quiktech.pos.dto.response.admin.TrafficReportResponse.StatusCount;
import com.quiktech.pos.dto.response.admin.TrafficReportResponse.Summary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Đọc thống kê lưu lượng API (bảng {@code api_traffic_*}, do {@link ApiTrafficRecorder} ghi) và
 * tình trạng tức thời của instance cho dashboard admin.
 *
 * <p>Khung 1 giờ / 24 giờ đọc bảng theo phút, khung 7 / 30 ngày đọc bảng theo giờ. Số liệu của
 * phút hiện tại chưa được ghi xuống DB nên dashboard trễ khoảng 1–2 phút.
 */
@Service
public class ApiTrafficService {

    /** Khung thời gian dashboard: độ dài, bảng nguồn và độ rộng mỗi điểm trên biểu đồ. */
    enum Range {
        HOUR("1h", Duration.ofHours(1), "api_traffic_minutely", Duration.ofMinutes(1)),
        DAY("24h", Duration.ofHours(24), "api_traffic_minutely", Duration.ofMinutes(15)),
        WEEK("7d", Duration.ofDays(7), "api_traffic_hourly", Duration.ofHours(2)),
        MONTH("30d", Duration.ofDays(30), "api_traffic_hourly", Duration.ofHours(6));

        final String code;
        final Duration length;
        final String table;
        final Duration step;

        Range(String code, Duration length, String table, Duration step) {
            this.code = code;
            this.length = length;
            this.table = table;
            this.step = step;
        }

        static Range of(String code) {
            return Arrays.stream(values()).filter(range -> range.code.equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("range must be one of 1h, 24h, 7d, 30d"));
        }
    }

    private static final String LATENCY_SUMS = "SUM(le_50) AS b0, SUM(le_100) AS b1, SUM(le_250) AS b2, SUM(le_500) AS b3, "
            + "SUM(le_1000) AS b4, SUM(le_2500) AS b5, SUM(le_5000) AS b6, SUM(gt_5000) AS b7";
    private static final String COUNTS = "COALESCE(SUM(request_count), 0) AS requests, "
            + "COALESCE(SUM(request_count) FILTER (WHERE status >= 500), 0) AS server_errors, "
            + "COALESCE(SUM(request_count) FILTER (WHERE status BETWEEN 400 AND 499), 0) AS client_errors, "
            + "COALESCE(SUM(total_duration_ms), 0) AS total_ms, COALESCE(MAX(max_duration_ms), 0) AS max_ms, "
            + LATENCY_SUMS;
    private static final int TOP_ENDPOINTS = 50;
    private static final int TOP_BUSINESSES = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;
    private final Clock clock;

    @Autowired
    public ApiTrafficService(NamedParameterJdbcTemplate jdbc, MeterRegistry meters, ObjectProvider<HealthEndpoint> healthEndpoint) {
        this(jdbc, meters, healthEndpoint, Clock.systemUTC());
    }

    ApiTrafficService(NamedParameterJdbcTemplate jdbc, MeterRegistry meters, ObjectProvider<HealthEndpoint> healthEndpoint, Clock clock) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.healthEndpoint = healthEndpoint;
        this.clock = clock;
    }

    public TrafficReportResponse report(String rangeCode) {
        Range range = Range.of(rangeCode);
        Instant to = clock.instant();
        // Căn đầu khung theo bước của biểu đồ để mỗi điểm phủ trọn một khoảng (date_bin cùng gốc epoch)
        long step = range.step.toSeconds();
        Instant from = Instant.ofEpochSecond(Math.floorDiv(to.minus(range.length).getEpochSecond(), step) * step);
        var args = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to))
                .addValue("step", step + " seconds");
        String where = " FROM " + range.table + " WHERE bucket_start >= :from AND bucket_start < :to";

        Summary summary = jdbc.queryForObject("SELECT " + COUNTS
                        + ", COALESCE(SUM(request_count) FILTER (WHERE status = 429), 0) AS rate_limited" + where, args,
                (rs, row) -> {
                    long[] latency = latency(rs);
                    long requests = rs.getLong("requests");
                    double minutes = Math.max(1, Duration.between(from, to).toSeconds() / 60.0);
                    return new Summary(requests, requests / minutes, rs.getLong("server_errors"), rs.getLong("client_errors"),
                            rs.getLong("rate_limited"), average(rs.getLong("total_ms"), requests),
                            percentile(latency, 0.50), percentile(latency, 0.95), percentile(latency, 0.99), rs.getLong("max_ms"));
                });

        Map<Instant, Point> byTime = new HashMap<>();
        jdbc.query("SELECT date_bin(CAST(:step AS interval), bucket_start, TIMESTAMPTZ 'epoch') AS t, " + COUNTS
                + where + " GROUP BY t", args, rs -> {
            Instant time = rs.getTimestamp("t").toInstant();
            long requests = rs.getLong("requests");
            byTime.put(time, new Point(time, requests, rs.getLong("server_errors"), rs.getLong("client_errors"),
                    average(rs.getLong("total_ms"), requests), percentile(latency(rs), 0.95)));
        });
        // Điểm không có request vẫn phải có trên biểu đồ (giá trị 0) để trục thời gian liên tục
        List<Point> series = new ArrayList<>();
        for (Instant time = from; time.isBefore(to); time = time.plus(range.step)) {
            series.add(byTime.getOrDefault(time, new Point(time, 0, 0, 0, null, null)));
        }

        List<StatusCount> statuses = jdbc.query("SELECT status, SUM(request_count) AS requests" + where
                        + " GROUP BY status ORDER BY requests DESC", args,
                (rs, row) -> new StatusCount(rs.getInt("status"), rs.getLong("requests")));

        List<Endpoint> endpoints = jdbc.query("SELECT method, route, " + COUNTS + where
                        + " GROUP BY method, route ORDER BY requests DESC LIMIT " + TOP_ENDPOINTS, args,
                (rs, row) -> {
                    long requests = rs.getLong("requests");
                    return new Endpoint(rs.getString("method"), rs.getString("route"), requests,
                            rs.getLong("server_errors"), rs.getLong("client_errors"),
                            average(rs.getLong("total_ms"), requests), percentile(latency(rs), 0.95), rs.getLong("max_ms"));
                });

        // Bảng business theo giờ: khung 1 giờ / 24 giờ được làm tròn xuống đầu giờ
        args.addValue("hourFrom", Timestamp.from(from.truncatedTo(ChronoUnit.HOURS)));
        List<BusinessUsage> businesses = jdbc.query("""
                        SELECT b.id, b.name, SUM(t.request_count) AS requests, SUM(t.error_count) AS errors,
                               SUM(t.total_duration_ms) AS total_ms
                        FROM api_traffic_business_hourly t JOIN businesses b ON b.id = t.business_id
                        WHERE t.bucket_start >= :hourFrom AND t.bucket_start < :to
                        GROUP BY b.id, b.name ORDER BY requests DESC LIMIT """ + " " + TOP_BUSINESSES, args,
                (rs, row) -> {
                    long requests = rs.getLong("requests");
                    return new BusinessUsage(rs.getLong("id"), rs.getString("name"), requests, rs.getLong("errors"),
                            average(rs.getLong("total_ms"), requests));
                });

        return new TrafficReportResponse(range.code, from, to, step, summary, series, statuses, endpoints, businesses);
    }

    /** Số đo tức thời của instance này (JVM, CPU, connection pool DB) và health của từng thành phần. */
    public SystemHealthResponse system() {
        String status = null;
        Map<String, String> components = new TreeMap<>();
        HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
        if (endpoint != null) {
            HealthComponent health = endpoint.health();
            status = health.getStatus().getCode();
            if (health instanceof CompositeHealth composite) {
                composite.getComponents().forEach((name, component) -> components.put(name, component.getStatus().getCode()));
            }
        }
        TimeGauge uptime = meters.find("process.uptime").timeGauge();
        return new SystemHealthResponse(
                clock.instant(), status, components,
                uptime == null ? null : (long) uptime.value(TimeUnit.SECONDS),
                toLong(gaugeSum("jvm.memory.used", "area", "heap")),
                toLong(gaugeSum("jvm.memory.max", "area", "heap")),
                gaugeSum("process.cpu.usage"),
                gaugeSum("system.cpu.usage"),
                toInt(gaugeSum("jvm.threads.live")),
                toInt(gaugeSum("hikaricp.connections.active")),
                toInt(gaugeSum("hikaricp.connections.idle")),
                toInt(gaugeSum("hikaricp.connections.max")),
                toInt(gaugeSum("hikaricp.connections.pending")));
    }

    // Cộng các gauge cùng tên (VD nhiều memory pool của heap); bỏ giá trị âm/NaN (JVM trả -1 khi không giới hạn)
    private Double gaugeSum(String name, String... tags) {
        var found = meters.find(name).tags(tags).gauges();
        if (found.isEmpty()) return null;
        double sum = 0;
        boolean any = false;
        for (Gauge gauge : found) {
            double value = gauge.value();
            if (!Double.isNaN(value) && value >= 0) {
                sum += value;
                any = true;
            }
        }
        return any ? sum : null;
    }

    private static Long toLong(Double value) {
        return value == null ? null : value.longValue();
    }

    private static Integer toInt(Double value) {
        return value == null ? null : value.intValue();
    }

    private static Double average(long totalMs, long requests) {
        return requests == 0 ? null : (double) totalMs / requests;
    }

    private static long[] latency(ResultSet rs) throws SQLException {
        long[] buckets = new long[ApiTrafficRecorder.LATENCY_BOUNDS_MS.length + 1];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = rs.getLong("b" + i);
        }
        return buckets;
    }

    /**
     * Phân vị ước lượng: mốc trên của khoảng đếm chứa phân vị {@code q}. {@code null} khi không có
     * request hoặc phân vị rơi vào khoảng cuối (trên 5000 ms, không có mốc trên).
     */
    static Long percentile(long[] buckets, double q) {
        long total = Arrays.stream(buckets).sum();
        if (total == 0) return null;
        long target = (long) Math.ceil(q * total);
        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i];
            if (cumulative >= target) {
                return i < ApiTrafficRecorder.LATENCY_BOUNDS_MS.length ? ApiTrafficRecorder.LATENCY_BOUNDS_MS[i] : null;
            }
        }
        return null;
    }
}
