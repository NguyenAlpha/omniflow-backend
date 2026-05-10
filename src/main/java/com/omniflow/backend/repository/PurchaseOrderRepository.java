package com.omniflow.backend.repository;

import com.omniflow.backend.entity.PurchaseOrder;
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
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByStoreIdAndOrderCode(Long storeId, String orderCode);

    Optional<PurchaseOrder> findByPublicId(UUID publicId);

    // Purchase orders by status
    @Query("""
        SELECT po FROM PurchaseOrder po 
        WHERE po.store.id = :storeId 
        AND po.status = :status
        ORDER BY po.createdAt DESC
    """)
    Page<PurchaseOrder> findByStoreAndStatus(
        @Param("storeId") Long storeId,
        @Param("status") String status,
        Pageable pageable
    );

    // Outstanding supplier debt
    @Query("""
        SELECT po FROM PurchaseOrder po 
        WHERE po.store.id = :storeId 
        AND po.debtAmount > 0 
        AND po.status = 'RECEIVED'
        ORDER BY po.createdAt DESC
    """)
    List<PurchaseOrder> findOutstandingPayments(@Param("storeId") Long storeId);

    // Total cost by date range
    @Query("""
        SELECT SUM(po.totalAmount) FROM PurchaseOrder po 
        WHERE po.store.id = :storeId 
        AND po.status = 'RECEIVED'
        AND po.createdAt BETWEEN :startDate AND :endDate
    """)
    Optional<BigDecimal> calculateTotalCostByDateRange(
        @Param("storeId") Long storeId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );

    // Find with items (JOIN FETCH to avoid N+1)
    @Query("""
        SELECT DISTINCT po FROM PurchaseOrder po
        LEFT JOIN FETCH po.purchaseOrderItems poi
        WHERE po.store.id = :storeId 
        AND po.id = :purchaseOrderId
    """)
    Optional<PurchaseOrder> findByIdWithItems(
        @Param("storeId") Long storeId,
        @Param("purchaseOrderId") Long purchaseOrderId
    );

    long countByStoreIdAndStatus(Long storeId, String status);

    List<PurchaseOrder> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    @Query("""
        SELECT DISTINCT p FROM PurchaseOrder p
        JOIN FETCH p.purchaseOrderItems pi JOIN FETCH pi.product
        WHERE p.publicId = :publicId
    """)
    Optional<PurchaseOrder> findByPublicIdWithItems(@Param("publicId") UUID publicId);
}

