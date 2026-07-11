package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByStoreIdAndOrderCode(Long storeId, String orderCode);

    /**
     * Lookup scoped theo store để chống IDOR: {@code @PreAuthorize} chỉ kiểm tra quyền
     * trên storeId của URL, không kiểm tra tenant của entity được load. Order không
     * thuộc store trong URL sẽ trả về empty → 404.
     */
    Optional<Order> findByPublicIdAndStoreId(UUID publicId, Long storeId);

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

    @Query(value = """
        SELECT o FROM Order o
        LEFT JOIN o.customer c
        WHERE o.store.id = :storeId
        AND (:status IS NULL OR o.status = :status)
        AND (:orderCode IS NULL OR LOWER(o.orderCode) LIKE :orderCode)
        AND (:customerPublicId IS NULL OR c.publicId = :customerPublicId)
        AND o.createdAt >= :fromDate
        AND o.createdAt <= :toDate
        ORDER BY o.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(o) FROM Order o
        LEFT JOIN o.customer c
        WHERE o.store.id = :storeId
        AND (:status IS NULL OR o.status = :status)
        AND (:orderCode IS NULL OR LOWER(o.orderCode) LIKE :orderCode)
        AND (:customerPublicId IS NULL OR c.publicId = :customerPublicId)
        AND o.createdAt >= :fromDate
        AND o.createdAt <= :toDate
        """)
    Page<Order> search(
        @Param("storeId") Long storeId,
        @Param("status") String status,
        @Param("orderCode") String orderCode,
        @Param("customerPublicId") UUID customerPublicId,
        @Param("fromDate") Instant fromDate,
        @Param("toDate") Instant toDate,
        Pageable pageable
    );

    // Điều kiện store.id: chống IDOR (xem Javadoc findByPublicIdAndStoreId)
    @Query("""
        SELECT DISTINCT o FROM Order o
        JOIN FETCH o.orderItems oi JOIN FETCH oi.product
        WHERE o.publicId = :publicId AND o.store.id = :storeId
    """)
    Optional<Order> findByPublicIdWithItems(@Param("publicId") UUID publicId, @Param("storeId") Long storeId);

    // Điều kiện store.id: chống IDOR (xem Javadoc findByPublicIdAndStoreId)
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.customer WHERE o.publicId = :publicId AND o.store.id = :storeId")
    Optional<Order> findByPublicIdWithCustomer(@Param("publicId") UUID publicId, @Param("storeId") Long storeId);

    @Query("""
        SELECT o FROM Order o
        WHERE o.customer.id = :customerId
        AND o.debtAmount > 0
        AND o.status = 'COMPLETED'
        ORDER BY o.createdAt ASC
    """)
    List<Order> findCompletedOrdersByCustomerWithDebt(@Param("customerId") Long customerId);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.customer WHERE o.store.id = :storeId ORDER BY o.createdAt DESC")
    List<Order> findRecentByStoreId(@Param("storeId") Long storeId, Pageable pageable);

    @Query("""
        SELECT o FROM Order o
        LEFT JOIN FETCH o.customer
        WHERE o.store.id = :storeId
        AND o.createdAt >= :from
        AND o.createdAt <= :to
        ORDER BY o.createdAt DESC
    """)
    List<Order> findForExport(@Param("storeId") Long storeId,
                              @Param("from") Instant from,
                              @Param("to") Instant to);
}

