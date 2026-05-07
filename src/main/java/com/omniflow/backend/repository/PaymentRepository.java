package com.omniflow.backend.repository;

import com.omniflow.backend.entity.Payment;
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
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPublicId(UUID publicId);

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
}

