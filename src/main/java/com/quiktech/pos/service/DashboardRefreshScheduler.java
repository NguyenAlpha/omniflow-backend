package com.quiktech.pos.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Slf4j
@Component
@RequiredArgsConstructor
public class DashboardRefreshScheduler {

    private final DataSource dataSource;

    @Scheduled(cron = "${dashboard.refresh.cron:0 */15 * * * *}")
    public void refreshViews() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_revenue");
            stmt.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_inventory_summary");
            log.info("Dashboard materialized views refreshed");
        } catch (Exception e) {
            log.error("Failed to refresh dashboard views", e);
        }
    }
}
