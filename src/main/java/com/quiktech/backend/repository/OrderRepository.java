package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByStoreIdAndOrderCode(Long storeId, String orderCode);

    Optional<Order> findByPublicId(UUID publicId);

    // Orders by store and status
    @Query("""
        SELECT o FROM Order o 
        WHERE o.store.id = :storeId 
        AND o.status = :status
        ORDER BY o.createdAt DESC
    """)
    Page<Order> findByStoreAndStatus(
        @Param("storeId") Long storeId,
        @Param("status") String status,
        Pageable pageable
    );

    // Orders with outstanding debt
    @Query("""
        SELECT o FROM Order o 
        WHERE o.store.id = :storeId 
        AND o.debtAmount > 0 
        AND o.status = 'COMPLETED'
        ORDER BY o.createdAt DESC
    """)
    List<Order> findOutstandingOrders(@Param("storeId") Long storeId);

    // Revenue by date range
    @Query("""
        SELECT SUM(o.totalAmount) FROM Order o 
        WHERE o.store.id = :storeId 
        AND o.status = 'COMPLETED'
        AND o.createdAt BETWEEN :startDate AND :endDate
    """)
    Optional<BigDecimal> calculateRevenueByDateRange(
        @Param("storeId") Long storeId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    // Find with items (JOIN FETCH to avoid N+1)
    @Query("""
        SELECT DISTINCT o FROM Order o
        LEFT JOIN FETCH o.orderItems oi
        WHERE o.store.id = :storeId 
        AND o.id = :orderId
    """)
    Optional<Order> findByIdWithItems(
        @Param("storeId") Long storeId,
        @Param("orderId") Long orderId
    );

    long countByStoreIdAndStatus(Long storeId, String status);

    List<Order> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    @Query("""
        SELECT DISTINCT o FROM Order o
        JOIN FETCH o.orderItems oi JOIN FETCH oi.product
        WHERE o.publicId = :publicId
    """)
    Optional<Order> findByPublicIdWithItems(@Param("publicId") UUID publicId);
}

