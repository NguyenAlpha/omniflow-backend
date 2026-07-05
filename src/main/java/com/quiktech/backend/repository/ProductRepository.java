package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByBusinessIdAndSkuAndDeletedAtIsNull(Long businessId, String sku);

    // JOIN FETCH bắt buộc — category/unit có @SQLRestriction/@Where,
    // nếu để lazy load Hibernate sẽ throw EntityNotFoundException khi gọi getPublicId()
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.publicId = :publicId AND p.deletedAt IS NULL")
    Optional<Product> findByPublicId(@Param("publicId") UUID publicId);

    List<Product> findByCategoryIdAndDeletedAtIsNull(Long categoryId);

    long countByBusinessIdAndDeletedAtIsNull(Long businessId);

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.business.id = :businessId AND p.deletedAt IS NULL")
    List<Product> findAllByBusinessId(@Param("businessId") Long businessId);

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.business.id = :businessId AND p.isActive = :isActive AND p.deletedAt IS NULL")
    List<Product> findAllByBusinessIdAndIsActive(@Param("businessId") Long businessId, @Param("isActive") Boolean isActive);

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.business.id = :businessId AND p.sku = :sku AND p.deletedAt IS NULL")
    Optional<Product> findByBusinessIdAndSkuWithDetails(@Param("businessId") Long businessId, @Param("sku") String sku);

    // Dùng sau Specification query để batch load category/unit, tránh N+1
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.id IN :ids")
    List<Product> findAllWithCategoryAndUnit(@Param("ids") List<Long> ids);

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.priceHistories WHERE p.publicId = :publicId AND p.deletedAt IS NULL")
    Optional<Product> findByPublicIdWithPriceHistories(@Param("publicId") UUID publicId);

    @Modifying
    @Query(value = "UPDATE products SET total_stock = (SELECT COALESCE(SUM(quantity), 0) FROM inventory WHERE product_id = :productId AND deleted_at IS NULL) WHERE id = :productId", nativeQuery = true)
    void recalculateTotalStock(@Param("productId") Long productId);
}
