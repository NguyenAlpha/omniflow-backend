package com.quiktech.pos.repository;

import com.quiktech.pos.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Lookup scoped theo store để chống IDOR: {@code @PreAuthorize} chỉ kiểm tra quyền
     * trên storeId của URL, không kiểm tra tenant của entity được load. Payment không
     * thuộc store trong URL sẽ trả về empty → 404.
     */
    Optional<Payment> findByPublicIdAndStoreId(UUID publicId, Long storeId);

    // Payments for customer
    List<Payment> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    // Payments for supplier
    List<Payment> findBySupplierIdOrderByCreatedAtDesc(Long supplierId);

    // Revenue collected by date range
    @Query("""
        SELECT SUM(p.amount) FROM Payment p 
        WHERE p.store.id = :storeId 
        AND p.customer IS NOT NULL
        AND p.createdAt BETWEEN :startDate AND :endDate
    """)
    Optional<BigDecimal> calculateCustomerPaymentsByDateRange(
        @Param("storeId") Long storeId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    // Total paid to suppliers
    @Query("""
        SELECT SUM(p.amount) FROM Payment p 
        WHERE p.store.id = :storeId 
        AND p.supplier IS NOT NULL
        AND p.createdAt BETWEEN :startDate AND :endDate
    """)
    Optional<BigDecimal> calculateSupplierPaymentsByDateRange(
        @Param("storeId") Long storeId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    Page<Payment> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

    List<Payment> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    @Query("""
        SELECT p FROM Payment p
        WHERE p.store.id = :storeId
        AND (:direction IS NULL
            OR (:direction = 'INCOME' AND p.supplier IS NULL)
            OR (:direction = 'EXPENSE' AND p.supplier IS NOT NULL))
        AND (:method IS NULL OR p.paymentMethod = :method)
        AND p.createdAt >= :fromDate
        AND p.createdAt <= :toDate
        ORDER BY p.createdAt DESC
    """)
    Page<Payment> search(
        @Param("storeId") Long storeId,
        @Param("direction") String direction,
        @Param("method") String method,
        @Param("fromDate") Instant fromDate,
        @Param("toDate") Instant toDate,
        Pageable pageable
    );

    @Query("""
        SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
        WHERE p.store.id = :storeId
        AND p.supplier IS NULL
        AND p.createdAt >= :fromDate
        AND p.createdAt <= :toDate
    """)
    BigDecimal sumIncome(
        @Param("storeId") Long storeId,
        @Param("fromDate") Instant fromDate,
        @Param("toDate") Instant toDate
    );

    @Query("""
        SELECT COALESCE(SUM(p.amount), 0) FROM Payment p
        WHERE p.store.id = :storeId
        AND p.supplier IS NOT NULL
        AND p.createdAt >= :fromDate
        AND p.createdAt <= :toDate
    """)
    BigDecimal sumExpense(
        @Param("storeId") Long storeId,
        @Param("fromDate") Instant fromDate,
        @Param("toDate") Instant toDate
    );
}

