package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Subscription;
import com.quiktech.backend.entity.enums.SubscriptionPlan;
import com.quiktech.backend.entity.enums.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByBusinessId(Long businessId);

    long countByPlanAndStatus(SubscriptionPlan plan, SubscriptionStatus status);

    // Lấy các subscription ACTIVE đã hết hạn và có pending downgrade — xử lý riêng từng cái
    @Query("""
        SELECT s FROM Subscription s
        WHERE s.status = :active
        AND s.expiresAt IS NOT NULL
        AND s.expiresAt < :now
        AND s.pendingPlan IS NOT NULL
    """)
    List<Subscription> findOverdueWithPendingPlan(
            @Param("active") SubscriptionStatus active,
            @Param("now") Instant now);

    // Bulk-expire subscription ACTIVE đã hết hạn và KHÔNG có pending downgrade.
    // Phải hạ 4 cột limit về FREE (scheduler truyền vào) ngay trong cùng UPDATE:
    // SubscriptionLimitService chỉ đọc maxXxx, không đọc status/expiresAt — nếu chỉ
    // đổi status, business EXPIRED giữ nguyên quyền lợi gói trả phí vĩnh viễn
    // (PRO limit = null = unlimited dù đã ngừng trả tiền).
    // Giữ nguyên s.plan làm record lịch sử — UI/renewal cần biết gói cũ là gì.
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Subscription s
        SET s.status = :expired,
            s.maxStores = :maxStores,
            s.maxStaff = :maxStaff,
            s.maxProducts = :maxProducts,
            s.maxWarehouses = :maxWarehouses
        WHERE s.status = :active
        AND s.expiresAt IS NOT NULL
        AND s.expiresAt < :now
        AND s.pendingPlan IS NULL
    """)
    int expireOverdue(
            @Param("expired") SubscriptionStatus expired,
            @Param("active") SubscriptionStatus active,
            @Param("now") Instant now,
            @Param("maxStores") Integer maxStores,
            @Param("maxStaff") Integer maxStaff,
            @Param("maxProducts") Integer maxProducts,
            @Param("maxWarehouses") Integer maxWarehouses);

    // Lấy các subscription ACTIVE sắp hết hạn trong khoảng (now, deadline] — JOIN FETCH để tránh N+1 khi đọc business.email
    @Query("""
        SELECT s FROM Subscription s
        JOIN FETCH s.business
        WHERE s.status = :active
        AND s.expiresAt IS NOT NULL
        AND s.expiresAt > :now
        AND s.expiresAt <= :deadline
    """)
    List<Subscription> findExpiringSoon(
            @Param("active") SubscriptionStatus active,
            @Param("now") Instant now,
            @Param("deadline") Instant deadline);

    long countByPlan(SubscriptionPlan plan);

    long countByStatus(SubscriptionStatus status);
}
