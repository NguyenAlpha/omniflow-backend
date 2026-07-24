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
        JOIN FETCH i.product p
        JOIN FETCH i.warehouse w
        WHERE w.store.id = :storeId
        AND i.quantity < p.minStockLevel
        AND i.deletedAt IS NULL
        ORDER BY i.quantity ASC
    """)
    List<Inventory> findLowStockItems(@Param("storeId") Long storeId);

    // Đếm bằng COUNT thay vì load list rồi .size() — tránh kéo toàn bộ entity vào heap
    @Query("""
        SELECT COUNT(i) FROM Inventory i
        JOIN i.product p
        JOIN i.warehouse w
        WHERE w.store.id = :storeId
        AND i.quantity < p.minStockLevel
        AND i.deletedAt IS NULL
    """)
    long countLowStockItems(@Param("storeId") Long storeId);

    long countByStoreIdAndDeletedAtIsNull(Long storeId);

    // Tổng tồn kho còn lại của một kho — chặn xóa kho khi vẫn còn hàng
    @Query("""
        SELECT COALESCE(SUM(i.quantity), 0) FROM Inventory i
        WHERE i.warehouse.id = :warehouseId
        AND i.deletedAt IS NULL
    """)
    java.math.BigDecimal sumQuantityByWarehouseId(@Param("warehouseId") Long warehouseId);

    // Total stock by product
    @Query("""
        SELECT SUM(i.quantity) FROM Inventory i
        WHERE i.product.id = :productId
        AND i.deletedAt IS NULL
    """)
    Optional<java.math.BigDecimal> getTotalQuantityByProduct(@Param("productId") Long productId);

    // Export — JOIN FETCH để tránh N+1 khi render Excel
    @Query("""
        SELECT i FROM Inventory i
        JOIN FETCH i.product p
        LEFT JOIN FETCH p.unit
        JOIN FETCH i.warehouse
        WHERE i.store.id = :storeId
        AND i.deletedAt IS NULL
    """)
    List<Inventory> findByStoreIdWithDetails(@Param("storeId") Long storeId);
}

