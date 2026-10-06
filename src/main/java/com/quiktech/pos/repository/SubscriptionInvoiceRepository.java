package com.quiktech.pos.repository;

import com.quiktech.pos.entity.SubscriptionInvoice;
import com.quiktech.pos.entity.enums.InvoiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, Long>, JpaSpecificationExecutor<SubscriptionInvoice> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SubscriptionInvoice i WHERE i.id = :id")
    Optional<SubscriptionInvoice> findByIdForUpdate(@Param("id") Long id);

    // Business owner: danh sách invoice theo business, mới nhất trước
    Page<SubscriptionInvoice> findByBusinessIdOrderByCreatedAtDesc(Long businessId, Pageable pageable);

    // Admin: tất cả invoice theo status, mới nhất trước
    Page<SubscriptionInvoice> findByStatusOrderByCreatedAtDesc(InvoiceStatus status, Pageable pageable);

    // Kiểm tra xem business có invoice PENDING nào không trước khi tạo mới
    @Query("""
        SELECT si FROM SubscriptionInvoice si
        WHERE si.business.id = :businessId
        AND si.status = :status
        ORDER BY si.createdAt DESC
    """)
    List<SubscriptionInvoice> findPendingByBusinessId(
            @Param("businessId") Long businessId,
            @Param("status") InvoiceStatus status);

    // Invoice mới nhất của business
    Optional<SubscriptionInvoice> findFirstByBusinessIdOrderByCreatedAtDesc(Long businessId);

    // Auto-huỷ các invoice PENDING quá hạn thanh toán (scheduler gọi hàng ngày) —
    // invoice PENDING không có TTL thì invoice tạo từ nhiều tháng trước (giá cũ)
    // vẫn confirm được. Chuyển sang FAILED thay vì DELETE để giữ lịch sử invoice.
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE SubscriptionInvoice si
        SET si.status = :failed, si.adminNote = :note
        WHERE si.status = :pending
        AND si.createdAt < :cutoff
    """)
    int failStalePending(
            @Param("failed") InvoiceStatus failed,
            @Param("pending") InvoiceStatus pending,
            @Param("cutoff") Instant cutoff,
            @Param("note") String note);

    long countByStatus(InvoiceStatus status);

    long countByBusinessIdAndStatus(Long businessId, InvoiceStatus status);

    @Query(value = """
        SELECT COALESCE(SUM(amount), 0)
        FROM subscription_invoices
        WHERE status = 'PAID'
        AND created_at >= :from
        AND created_at < :to
    """, nativeQuery = true)
    BigDecimal sumPaidAmountBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query(value = """
        SELECT TO_CHAR(DATE_TRUNC('month', created_at), 'YYYY-MM') AS month,
               COALESCE(SUM(amount), 0) AS amount
        FROM subscription_invoices
        WHERE status = 'PAID'
        AND created_at >= :from
        GROUP BY DATE_TRUNC('month', created_at)
        ORDER BY DATE_TRUNC('month', created_at)
    """, nativeQuery = true)
    List<Object[]> monthlyRevenueSince(@Param("from") Instant from);
}
