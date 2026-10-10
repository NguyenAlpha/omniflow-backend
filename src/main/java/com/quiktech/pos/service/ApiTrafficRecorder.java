package com.quiktech.pos.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Cộng dồn lưu lượng API trong bộ nhớ và mỗi phút ghi xuống DB (bảng {@code api_traffic_*}, V11).
 *
 * <p>{@code ApiTrafficFilter} gọi {@link #record} cho mỗi request {@code /api/**} — chỉ cộng vào
 * map trong bộ nhớ, không chạm DB. {@link #flushCompletedMinutes} chạy mỗi phút, lấy các bucket
 * của những phút <b>đã kết thúc</b> (phút hiện tại vẫn đang nhận request) và UPSERT cộng thêm
 * vào bảng theo phút và theo giờ — nhiều instance cùng ghi một bucket vẫn cộng đúng. Khi tắt
 * app, mọi bucket còn lại được ghi nốt.
 *
 * <p>Thống kê theo business: request có path variable {@code businessId} được gán thẳng; chỉ
 * có {@code storeId} thì tra business của store lúc flush (cache lại, quan hệ store → business
 * không đổi). Store không tồn tại bị bỏ qua.
 */
@Slf4j
@Component
public class ApiTrafficRecorder {

    /**
     * Mốc (ms) của các khoảng đếm thời gian xử lý — khớp cột của bảng: le_50 = (0, 50],
     * le_100 = (50, 100], …, le_5000 = (2500, 5000], gt_5000 = trên 5000 ms.
     */
    static final long[] LATENCY_BOUNDS_MS = {50, 100, 250, 500, 1000, 2500, 5000};

    private static final String ROUTE_COLUMNS =
            "bucket_start, method, route, status, request_count, total_duration_ms, max_duration_ms, "
                    + "le_50, le_100, le_250, le_500, le_1000, le_2500, le_5000, gt_5000";
    private static final String ROUTE_VALUES =
            ":bucket, :method, :route, :status, :count, :total, :max, "
                    + ":b0, :b1, :b2, :b3, :b4, :b5, :b6, :b7";
    private static final String ROUTE_UPDATE = """
            request_count = t.request_count + EXCLUDED.request_count,
            total_duration_ms = t.total_duration_ms + EXCLUDED.total_duration_ms,
            max_duration_ms = GREATEST(t.max_duration_ms, EXCLUDED.max_duration_ms),
            le_50 = t.le_50 + EXCLUDED.le_50, le_100 = t.le_100 + EXCLUDED.le_100,
            le_250 = t.le_250 + EXCLUDED.le_250, le_500 = t.le_500 + EXCLUDED.le_500,
            le_1000 = t.le_1000 + EXCLUDED.le_1000, le_2500 = t.le_2500 + EXCLUDED.le_2500,
            le_5000 = t.le_5000 + EXCLUDED.le_5000, gt_5000 = t.gt_5000 + EXCLUDED.gt_5000""";

    record RouteKey(Instant minute, String method, String route, int status) {}

    /** Đúng một trong hai id khác null. */
    record BusinessKey(Instant minute, Long businessId, Long storeId) {}

    static final class RouteStats {
        long count;
        long totalMs;
        long maxMs;
        final long[] buckets = new long[LATENCY_BOUNDS_MS.length + 1];

        synchronized void add(long ms) {
            count++;
            totalMs += ms;
            maxMs = Math.max(maxMs, ms);
            int bucket = 0;
            while (bucket < LATENCY_BOUNDS_MS.length && ms > LATENCY_BOUNDS_MS[bucket]) bucket++;
            buckets[bucket]++;
        }
    }

    static final class BusinessStats {
        long count;
        long errors;
        long totalMs;

        synchronized void add(long ms, boolean error) {
            count++;
            totalMs += ms;
            if (error) errors++;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final Map<RouteKey, RouteStats> routes = new ConcurrentHashMap<>();
    private final Map<BusinessKey, BusinessStats> businesses = new ConcurrentHashMap<>();
    private final Map<Long, Long> storeBusiness = new ConcurrentHashMap<>();

    @Autowired
    public ApiTrafficRecorder(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    /** Constructor cho test — nhận {@code clock} giả để điều khiển phút hiện tại. */
    ApiTrafficRecorder(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Ghi nhận một request đã xử lý xong. Gọi trên mọi request nên chỉ thao tác bộ nhớ.
     *
     * @param route      mẫu route Spring, hoặc {@code (unmatched)} nếu request không tới controller
     * @param businessId path variable {@code businessId} nếu có
     * @param storeId    path variable {@code storeId} nếu có (dùng khi không có businessId)
     */
    public void record(String method, String route, int status, long durationMs, Long businessId, Long storeId) {
        Instant minute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        routes.computeIfAbsent(new RouteKey(minute, method, route, status), key -> new RouteStats()).add(durationMs);
        if (businessId != null || storeId != null) {
            var key = new BusinessKey(minute, businessId, businessId != null ? null : storeId);
            businesses.computeIfAbsent(key, ignored -> new BusinessStats()).add(durationMs, status >= 500);
        }
    }

    @Scheduled(fixedDelayString = "${api-traffic.flush-interval-ms:60000}",
            initialDelayString = "${api-traffic.flush-interval-ms:60000}")
    public void flushCompletedMinutes() {
        Instant currentMinute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        flush(minute -> minute.isBefore(currentMinute));
    }

    @PreDestroy
    public void flushAll() {
        flush(minute -> true);
    }

    /** Xóa dữ liệu quá hạn: theo phút giữ 2 ngày, theo giờ và theo business giữ 30 ngày. */
    @Scheduled(cron = "${api-traffic.cleanup-cron:0 7 * * * *}")
    public void deleteExpired() {
        Instant now = clock.instant();
        var args = new MapSqlParameterSource()
                .addValue("minuteCutoff", Timestamp.from(now.minus(2, ChronoUnit.DAYS)))
                .addValue("hourCutoff", Timestamp.from(now.minus(30, ChronoUnit.DAYS)));
        jdbc.update("DELETE FROM api_traffic_minutely WHERE bucket_start < :minuteCutoff", args);
        jdbc.update("DELETE FROM api_traffic_hourly WHERE bucket_start < :hourCutoff", args);
        jdbc.update("DELETE FROM api_traffic_business_hourly WHERE bucket_start < :hourCutoff", args);
    }

    private synchronized void flush(Predicate<Instant> due) {
        List<MapSqlParameterSource> minuteRows = new ArrayList<>();
        List<MapSqlParameterSource> hourRows = new ArrayList<>();
        for (RouteKey key : List.copyOf(routes.keySet())) {
            if (!due.test(key.minute())) continue;
            RouteStats stats = routes.remove(key);
            if (stats == null) continue;
            minuteRows.add(routeRow(key.minute(), key, stats));
            hourRows.add(routeRow(key.minute().truncatedTo(ChronoUnit.HOURS), key, stats));
        }

        Map<BusinessKey, BusinessStats> businessDue = new HashMap<>();
        for (BusinessKey key : List.copyOf(businesses.keySet())) {
            if (!due.test(key.minute())) continue;
            BusinessStats stats = businesses.remove(key);
            if (stats != null) businessDue.put(key, stats);
        }

        try {
            if (!minuteRows.isEmpty()) {
                jdbc.batchUpdate(upsert("api_traffic_minutely"), minuteRows.toArray(MapSqlParameterSource[]::new));
                jdbc.batchUpdate(upsert("api_traffic_hourly"), hourRows.toArray(MapSqlParameterSource[]::new));
            }
            if (!businessDue.isEmpty()) {
                writeBusinesses(businessDue);
            }
        } catch (RuntimeException failure) {
            // Thống kê không được làm ảnh hưởng nghiệp vụ — mất số liệu của lượt này thì chấp nhận
            log.warn("Could not persist API traffic stats ({} route rows): {}", minuteRows.size(), failure.getMessage());
        }
    }

    private void writeBusinesses(Map<BusinessKey, BusinessStats> due) {
        resolveStores(due.keySet());
        // Gộp các phút cùng giờ của cùng business trước khi ghi
        Map<HourBusiness, long[]> perHour = new HashMap<>();
        due.forEach((key, stats) -> {
            Long businessId = key.businessId() != null ? key.businessId() : storeBusiness.get(key.storeId());
            if (businessId == null) return;
            long[] total = perHour.computeIfAbsent(
                    new HourBusiness(key.minute().truncatedTo(ChronoUnit.HOURS), businessId), ignored -> new long[3]);
            total[0] += stats.count;
            total[1] += stats.errors;
            total[2] += stats.totalMs;
        });
        if (perHour.isEmpty()) return;
        var rows = perHour.entrySet().stream().map(entry -> new MapSqlParameterSource()
                        .addValue("bucket", Timestamp.from(entry.getKey().hour()))
                        .addValue("business", entry.getKey().businessId())
                        .addValue("count", entry.getValue()[0])
                        .addValue("errors", entry.getValue()[1])
                        .addValue("total", entry.getValue()[2]))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO api_traffic_business_hourly AS t (bucket_start, business_id, request_count, error_count, total_duration_ms)
                VALUES (:bucket, :business, :count, :errors, :total)
                ON CONFLICT (bucket_start, business_id) DO UPDATE SET
                    request_count = t.request_count + EXCLUDED.request_count,
                    error_count = t.error_count + EXCLUDED.error_count,
                    total_duration_ms = t.total_duration_ms + EXCLUDED.total_duration_ms""", rows);
    }

    private record HourBusiness(Instant hour, long businessId) {}

    private void resolveStores(Set<BusinessKey> keys) {
        Set<Long> unknown = new HashSet<>();
        for (BusinessKey key : keys) {
            if (key.storeId() != null && !storeBusiness.containsKey(key.storeId())) unknown.add(key.storeId());
        }
        if (unknown.isEmpty()) return;
        jdbc.query("SELECT id, business_id FROM stores WHERE id IN (:ids)", new MapSqlParameterSource("ids", unknown),
                row -> { storeBusiness.put(row.getLong("id"), row.getLong("business_id")); });
    }

    private static String upsert(String table) {
        return "INSERT INTO " + table + " AS t (" + ROUTE_COLUMNS + ") VALUES (" + ROUTE_VALUES + ")"
                + " ON CONFLICT (bucket_start, method, route, status) DO UPDATE SET " + ROUTE_UPDATE;
    }

    private static MapSqlParameterSource routeRow(Instant bucket, RouteKey key, RouteStats stats) {
        var row = new MapSqlParameterSource()
                .addValue("bucket", Timestamp.from(bucket))
                .addValue("method", key.method())
                .addValue("route", key.route())
                .addValue("status", key.status())
                .addValue("count", stats.count)
                .addValue("total", stats.totalMs)
                .addValue("max", stats.maxMs);
        for (int i = 0; i < stats.buckets.length; i++) {
            row.addValue("b" + i, stats.buckets[i]);
        }
        return row;
    }
}
