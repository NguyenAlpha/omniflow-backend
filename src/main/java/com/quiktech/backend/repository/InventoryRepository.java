package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductIdAndWarehouseId(Long productId, Long warehouseId);

    List<Inventory> findByProductIdAndDeletedAtIsNull(Long productId);

    List<Inventory> findByWarehouseIdAndDeletedAtIsNull(Long warehouseId);

    List<Inventory> findByStoreIdAndDeletedAtIsNull(Long storeId);

    Optional<Inventory> findByPublicId(UUID publicId);

    // Low stock alert
    @Query("""
        SELECT i FROM Inventory i 
        WHERE i.warehouse.store.id = :storeId 
        AND i.quantity < i.product.minStockLevel
        AND i.deletedAt IS NULL
        ORDER BY i.quantity ASC
    """)
    List<Inventory> findLowStockItems(@Param("storeId") Long storeId);

    // Total stock by product
    @Query("""
        SELECT SUM(i.quantity) FROM Inventory i 
        WHERE i.product.id = :productId 
        AND i.deletedAt IS NULL
    """)
    Optional<java.math.BigDecimal> getTotalQuantityByProduct(@Param("productId") Long productId);
}

