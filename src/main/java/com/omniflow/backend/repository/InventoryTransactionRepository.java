package com.omniflow.backend.repository;

import com.omniflow.backend.entity.InventoryTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {

    List<InventoryTransaction> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    List<InventoryTransaction> findByProductIdOrderByCreatedAtDesc(Long productId);

    List<InventoryTransaction> findByWarehouseIdOrderByCreatedAtDesc(Long warehouseId);

    List<InventoryTransaction> findByTypeOrderByCreatedAtDesc(String type);

    List<InventoryTransaction> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    List<InventoryTransaction> findByPurchaseOrderIdOrderByCreatedAtDesc(Long purchaseOrderId);

    // Pagination
    Page<InventoryTransaction> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

    // Date range query
    @Query("""
        SELECT it FROM InventoryTransaction it 
        WHERE it.store.id = :storeId 
        AND it.createdAt BETWEEN :startDate AND :endDate
        ORDER BY it.createdAt DESC
    """)
    List<InventoryTransaction> findByStoreAndDateRange(
        @Param("storeId") Long storeId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}

