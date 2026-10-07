package com.quiktech.pos.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Materializes committed business state even when no user is online. */
@Service
@RequiredArgsConstructor
public class NotificationProjector {
    private final JdbcTemplate jdbc;

    // Reused by live alerts so inactive/deleted stock never contributes to the count.
    static final String LOW_STOCK_FROM = """
            FROM inventory i
            JOIN products p ON p.id = i.product_id
            JOIN warehouses w ON w.id = i.warehouse_id
            JOIN stores s ON s.id = i.store_id AND s.id = w.store_id
            JOIN businesses b ON b.id = s.business_id AND b.id = p.business_id
            WHERE i.deleted_at IS NULL AND p.deleted_at IS NULL AND w.deleted_at IS NULL
              AND s.deleted_at IS NULL AND b.deleted_at IS NULL
              AND p.is_active AND w.is_active AND s.is_active AND b.is_active
              AND i.quantity < p.min_stock_level
            """;

    @Scheduled(fixedDelayString = "${notifications.refresh-ms:60000}", initialDelayString = "${notifications.initial-delay-ms:10000}")
    @Transactional
    public void refresh() {
        // One projector transaction across API instances. Unique keys also protect retries.
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(81734021)", Boolean.class))) return;
        jdbc.update("""
                UPDATE notifications n SET resolved_at = now(), event_key = NULL
                WHERE n.type = 'LOW_STOCK' AND n.resolved_at IS NULL
                  AND NOT EXISTS (SELECT 1
                """ + LOW_STOCK_FROM + " AND i.id = n.source_id)");
        jdbc.update("""
                INSERT INTO notifications (business_id, store_id, type, source_id, event_key, subject, detail, target_path)
                SELECT s.business_id, s.id, 'LOW_STOCK', i.id, 'stock:' || i.id, p.name,
                    w.name || ' · ' || p.sku || ' · ' || i.quantity || ' / ' || p.min_stock_level,
                    '/inventory?item=' || i.public_id
                """ + LOW_STOCK_FROM + " ORDER BY i.id ON CONFLICT (event_key) DO NOTHING");

        jdbc.update("""
                INSERT INTO notifications (business_id, type, source_id, event_key, subject, detail, target_path, created_at)
                SELECT i.business_id, CASE WHEN i.status = 'PAID' THEN 'INVOICE_PAID' ELSE 'INVOICE_FAILED' END,
                    i.id, 'invoice:' || i.id || ':' || i.status, i.plan || ' · #' || i.id,
                    i.admin_note, '/subscription?invoiceId=' || i.id, COALESCE(i.confirmed_at, i.updated_at)
                FROM subscription_invoices i JOIN businesses b ON b.id = i.business_id
                WHERE i.status IN ('PAID', 'FAILED') AND b.deleted_at IS NULL AND b.is_active
                  AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.event_key = 'invoice:' || i.id || ':' || i.status)
                ORDER BY i.id ON CONFLICT (event_key) DO NOTHING
                """);

        jdbc.update("""
                INSERT INTO notifications (business_id, type, source_id, event_key, subject, detail, target_path)
                SELECT s.business_id, 'SUBSCRIPTION_EXPIRING', s.id,
                    'expiring:' || s.id || ':' || EXTRACT(EPOCH FROM s.expires_at), s.plan,
                    to_char(s.expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '/subscription'
                FROM subscriptions s JOIN businesses b ON b.id = s.business_id
                WHERE s.plan <> 'FREE' AND s.status = 'ACTIVE' AND b.deleted_at IS NULL AND b.is_active
                    AND s.expires_at > now() AND s.expires_at <= now() + INTERVAL '7 days'
                ON CONFLICT (event_key) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO notifications (business_id, type, source_id, event_key, subject, detail, target_path)
                SELECT s.business_id, 'SUBSCRIPTION_EXPIRED', s.id,
                    'expired:' || s.id || ':' || EXTRACT(EPOCH FROM s.expires_at), s.plan,
                    to_char(s.expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), '/subscription'
                FROM subscriptions s JOIN businesses b ON b.id = s.business_id
                WHERE s.plan <> 'FREE' AND s.status IN ('ACTIVE', 'EXPIRED')
                    AND b.deleted_at IS NULL AND b.is_active AND s.expires_at <= now()
                ON CONFLICT (event_key) DO NOTHING
                """);
        // Obsolete expiry warnings stay in history but stop contributing to unread counts.
        jdbc.update("""
                UPDATE notifications n SET resolved_at = now()
                WHERE n.type IN ('SUBSCRIPTION_EXPIRING', 'SUBSCRIPTION_EXPIRED') AND n.resolved_at IS NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM subscriptions s WHERE s.id = n.source_id AND s.plan <> 'FREE'
                      AND ((n.type = 'SUBSCRIPTION_EXPIRING' AND s.status = 'ACTIVE' AND s.expires_at > now()
                            AND n.event_key = 'expiring:' || s.id || ':' || EXTRACT(EPOCH FROM s.expires_at))
                        OR (n.type = 'SUBSCRIPTION_EXPIRED' AND s.status IN ('ACTIVE', 'EXPIRED') AND s.expires_at <= now()
                            AND n.event_key = 'expired:' || s.id || ':' || EXTRACT(EPOCH FROM s.expires_at)))
                  )
                """);
    }
}
