package com.omniflow.backend.repository;

import com.omniflow.backend.entity.SubscriptionInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, Long> {

    // Invoices by store
    Page<SubscriptionInvoice> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

    // Pending invoices for store
    @Query("""
        SELECT si FROM SubscriptionInvoice si 
        WHERE si.store.id = :storeId 
        AND si.status = 'PENDING'
        ORDER BY si.createdAt DESC
    """)
    List<SubscriptionInvoice> findPendingInvoices(@Param("storeId") Long storeId);

    // Current/latest invoice
    @Query("""
        SELECT si FROM SubscriptionInvoice si 
        WHERE si.store.id = :storeId 
        ORDER BY si.createdAt DESC
        LIMIT 1
    """)
    SubscriptionInvoice findLatestInvoice(@Param("storeId") Long storeId);

    // Invoices by status
    Page<SubscriptionInvoice> findByStoreIdAndStatusOrderByCreatedAtDesc(
        Long storeId,
        String status,
        Pageable pageable
    );

    // Invoices by date range
    List<SubscriptionInvoice> findByStoreIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        Long storeId,
        LocalDateTime startDate,
        LocalDateTime endDate
    );

    long countByStoreIdAndStatus(Long storeId, String status);
}

