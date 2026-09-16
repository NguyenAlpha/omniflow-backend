package com.quiktech.pos.repository;

import com.quiktech.pos.entity.PurchaseOrder;
import com.quiktech.pos.entity.enums.PurchaseOrderStatus;
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
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByStoreIdAndOrderCode(Long storeId, String orderCode);

    /**
     * Lookup scoped theo store để chống IDOR: {@code @PreAuthorize} chỉ kiểm tra quyền
     * trên storeId của URL, không kiểm tra tenant của entity được load. PurchaseOrder không
     * thuộc store trong URL sẽ trả về empty → 404.
     */
    Optional<PurchaseOrder> findByPublicIdAndStoreId(UUID publicId, Long storeId);

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

    @Query(value = """
        SELECT po FROM PurchaseOrder po
        WHERE po.store.id = :storeId
        AND (:status IS NULL OR po.status = :status)
        AND (:orderCode IS NULL OR LOWER(po.orderCode) LIKE :orderCode)
        AND po.createdAt >= :fromDate
        AND po.createdAt <= :toDate
        ORDER BY po.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(po) FROM PurchaseOrder po
        WHERE po.store.id = :storeId
        AND (:status IS NULL OR po.status = :status)
        AND (:orderCode IS NULL OR LOWER(po.orderCode) LIKE :orderCode)
        AND po.createdAt >= :fromDate
        AND po.createdAt <= :toDate
        """)
    Page<PurchaseOrder> search(
        @Param("storeId") Long storeId,
        @Param("status") PurchaseOrderStatus status,
        @Param("orderCode") String orderCode,
        @Param("fromDate") Instant fromDate,
        @Param("toDate") Instant toDate,
        Pageable pageable
    );

    // Điều kiện store.id: chống IDOR (xem Javadoc findByPublicIdAndStoreId)
    @Query("""
        SELECT DISTINCT p FROM PurchaseOrder p
        JOIN FETCH p.purchaseOrderItems pi JOIN FETCH pi.product
        WHERE p.publicId = :publicId AND p.store.id = :storeId
    """)
    Optional<PurchaseOrder> findByPublicIdWithItems(@Param("publicId") UUID publicId, @Param("storeId") Long storeId);

    @Query("""
        SELECT po FROM PurchaseOrder po
        WHERE po.supplier.id = :supplierId
        AND po.debtAmount > 0
        AND po.status = 'RECEIVED'
        ORDER BY po.createdAt ASC
    """)
    List<PurchaseOrder> findReceivedOrdersBySupplierWithDebt(@Param("supplierId") Long supplierId);

    // Export PDF — JOIN FETCH tất cả quan hệ để tránh N+1
    @Query("""
        SELECT DISTINCT po FROM PurchaseOrder po
        JOIN FETCH po.supplier
        JOIN FETCH po.store
        JOIN FETCH po.warehouse
        JOIN FETCH po.purchaseOrderItems poi
        JOIN FETCH poi.product
        WHERE po.publicId = :publicId AND po.store.id = :storeId
    """)
    Optional<PurchaseOrder> findForExport(@Param("publicId") UUID publicId,
                                          @Param("storeId") Long storeId);
}

