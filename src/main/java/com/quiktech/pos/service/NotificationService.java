package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.notification.*;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.security.BusinessAccessEvaluator;
import com.quiktech.pos.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final StoreRepository stores;
    private final BusinessAccessEvaluator businessAccess;

    private static final String VISIBLE = """
            n.business_id = :business AND (n.store_id = :store OR (n.store_id IS NULL AND :owner))
            """;
    private static final String WITH_READS = """
            FROM notifications n LEFT JOIN notification_reads r ON r.notification_id = n.id AND r.user_id = :user
            WHERE
            """ + VISIBLE;

    private MapSqlParameterSource scope(Long storeId, Authentication auth) {
        Long businessId = stores.findBusinessIdByStoreId(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
        return new MapSqlParameterSource("store", storeId).addValue("business", businessId)
                .addValue("user", ((UserPrincipal) auth.getPrincipal()).userId())
                .addValue("owner", businessAccess.isOwner(businessId, auth));
    }

    public NotificationSummaryResponse summary(Long storeId, Authentication auth) {
        var args = scope(storeId, auth);
        // COUNT ở DB thay vì load toàn bộ entity (JOIN FETCH) rồi .size().
        long lowStock = jdbc.queryForObject("SELECT COUNT(*) " + NotificationProjector.LOW_STOCK_FROM + " AND s.id = :store", args, Long.class);
        long pending = Boolean.TRUE.equals(args.getValue("owner")) ? jdbc.queryForObject(
                "SELECT COUNT(*) FROM subscription_invoices WHERE business_id = :business AND status = 'PENDING'", args, Long.class) : 0;
        return jdbc.queryForObject("SELECT COUNT(*) FILTER (WHERE r.read_at IS NULL AND n.resolved_at IS NULL) AS unread, COALESCE(MAX(n.id), 0) AS latest " + WITH_READS,
                args, (rs, row) -> new NotificationSummaryResponse(lowStock, pending, rs.getLong("unread"), rs.getLong("latest")));
    }

    public NotificationPageResponse list(Long storeId, Authentication auth, Long cursor, int size, boolean unreadOnly) {
        if (size < 1 || size > 50 || (cursor != null && cursor < 1)) throw new IllegalArgumentException("Invalid notification pagination");
        var args = scope(storeId, auth).addValue("cursor", cursor == null ? Long.MAX_VALUE : cursor).addValue("limit", size + 1);
        var rows = jdbc.query("SELECT n.*, (r.read_at IS NOT NULL OR n.resolved_at IS NOT NULL) AS is_read " + WITH_READS
                        + " AND n.id < :cursor " + (unreadOnly ? " AND r.read_at IS NULL AND n.resolved_at IS NULL " : "")
                        + " ORDER BY n.id DESC LIMIT :limit", args,
                (rs, row) -> new NotificationResponse(rs.getLong("id"), rs.getString("type"), rs.getString("subject"),
                        rs.getString("detail"), rs.getString("target_path"), rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("is_read"), rs.getTimestamp("resolved_at") != null));
        boolean more = rows.size() > size;
        var content = more ? rows.subList(0, size) : rows;
        return new NotificationPageResponse(content, more ? content.get(content.size() - 1).id() : null);
    }

    public List<LowStockItemResponse> lowStock(Long storeId, Authentication auth) {
        return jdbc.query("SELECT p.name, p.sku, i.quantity, p.min_stock_level, w.name AS warehouse "
                        + NotificationProjector.LOW_STOCK_FROM + " AND s.id = :store ORDER BY i.quantity, i.id LIMIT 10", scope(storeId, auth),
                (rs, row) -> new LowStockItemResponse(rs.getString("name"), rs.getString("sku"), rs.getBigDecimal("quantity"),
                        rs.getInt("min_stock_level"), rs.getString("warehouse")));
    }

    @Transactional
    public void markRead(Long storeId, Long id, Authentication auth) {
        var args = scope(storeId, auth).addValue("id", id);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM notifications n WHERE " + VISIBLE + " AND n.id = :id", args, Long.class) == 0) {
            throw new ResourceNotFoundException(ErrorCode.NOTIFICATION_NOT_FOUND, "Notification not found");
        }
        jdbc.update("INSERT INTO notification_reads (notification_id, user_id) VALUES (:id, :user) ON CONFLICT DO NOTHING", args);
    }

    @Transactional
    public void markAllRead(Long storeId, long throughId, Authentication auth) {
        if (throughId < 0) throw new IllegalArgumentException("Invalid notification boundary");
        jdbc.update("INSERT INTO notification_reads (notification_id, user_id) SELECT n.id, :user FROM notifications n WHERE "
                        + VISIBLE + " AND n.id <= :through AND n.resolved_at IS NULL ON CONFLICT DO NOTHING",
                scope(storeId, auth).addValue("through", throughId));
    }
}
