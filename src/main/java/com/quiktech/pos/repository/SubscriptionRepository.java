package com.quiktech.pos.repository;

import com.quiktech.pos.entity.Subscription;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Khóa row subscription (SELECT ... FOR UPDATE) — dùng cho check plan limit.
     * Mẫu check-then-insert (COUNT >= max → throw, rồi INSERT) không tự an toàn:
     * 2 request song song cùng đếm được N < max rồi cùng insert → vượt limit gói.
     * Mọi luồng tạo resource cùng business phải serialize qua lock này; lock giữ
     * tới khi transaction của caller commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.business.id = :businessId")
    Optional<Subscription> findByBusinessIdForUpdate(@Param("businessId") Long businessId);

    long countByPlanAndStatus(SubscriptionPlan plan, SubscriptionStatus status);

    long countByStatusNot(SubscriptionStatus status);

    // Admin sửa limit của gói → cập nhật bản sao limit của mọi sub ACTIVE đang dùng gói đó
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Subscription s
        SET s.maxStores = :maxStores,
            s.maxStaff = :maxStaff,
            s.maxProducts = :maxProducts,
            s.maxWarehouses = :maxWarehouses
        WHERE s.plan = :plan AND s.status = :active
    """)
    int applyPlanLimits(
            @Param("plan") SubscriptionPlan plan,
            @Param("active") SubscriptionStatus active,
            @Param("maxStores") Integer maxStores,
            @Param("maxStaff") Integer maxStaff,
            @Param("maxProducts") Integer maxProducts,
            @Param("maxWarehouses") Integer maxWarehouses);

    // Sub không ACTIVE (EXPIRED) mang limit FREE dù plan giữ gói cũ (xem expireOverdue)
    // → admin sửa limit FREE thì cũng phải cập nhật nhóm này
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Subscription s
        SET s.maxStores = :maxStores,
            s.maxStaff = :maxStaff,
            s.maxProducts = :maxProducts,
            s.maxWarehouses = :maxWarehouses
        WHERE s.status <> :active
    """)
    int applyFreeLimitsToInactive(
            @Param("active") SubscriptionStatus active,
            @Param("maxStores") Integer maxStores,
            @Param("maxStaff") Integer maxStaff,
            @Param("maxProducts") Integer maxProducts,
            @Param("maxWarehouses") Integer maxWarehouses);

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

    // Lấy các subscription ACTIVE sắp hết hạn trong khoảng (now, deadline] — JOIN FETCH để tránh N+1 khi đọc business.email.
    // Chỉ lấy sub CHƯA gửi email cảnh báo cho chu kỳ hiện tại (expiryWarningSentAt IS NULL)
    // — scheduler chạy hàng ngày, không có filter này mỗi sub nhận 7 email lặp trong 7 ngày cuối.
    @Query("""
        SELECT s FROM Subscription s
        JOIN FETCH s.business
        WHERE s.status = :active
        AND s.expiresAt IS NOT NULL
        AND s.expiresAt > :now
        AND s.expiresAt <= :deadline
        AND s.expiryWarningSentAt IS NULL
    """)
    List<Subscription> findExpiringSoon(
            @Param("active") SubscriptionStatus active,
            @Param("now") Instant now,
            @Param("deadline") Instant deadline);

    long countByPlan(SubscriptionPlan plan);

    long countByStatus(SubscriptionStatus status);
}
