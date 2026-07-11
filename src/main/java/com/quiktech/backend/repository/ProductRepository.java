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
    /**
     * Lookup scoped theo business để chống IDOR: {@code @PreAuthorize} chỉ kiểm tra quyền
     * trên businessId của URL, không kiểm tra tenant của entity được load. Nếu chỉ tra theo
     * publicId, thành viên business A có thể đọc/sửa/xóa product của business B khi đoán được
     * UUID. Query này đảm bảo product không thuộc business trong URL sẽ trả về 404.
     */
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.business.id = :businessId AND p.publicId = :publicId AND p.deletedAt IS NULL")
    Optional<Product> findByBusinessIdAndPublicId(@Param("businessId") Long businessId, @Param("publicId") UUID publicId);

    List<Product> findByCategoryIdAndDeletedAtIsNull(Long categoryId);

    long countByBusinessIdAndDeletedAtIsNull(Long businessId);

    // Guard xóa category/unit: đếm product còn sống đang tham chiếu trước khi cho phép soft-delete
    long countByCategoryIdAndDeletedAtIsNull(Long categoryId);

    long countByUnitIdAndDeletedAtIsNull(Long unitId);

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.business.id = :businessId AND p.deletedAt IS NULL")
    List<Product> findAllByBusinessId(@Param("businessId") Long businessId);

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.business.id = :businessId AND p.isActive = :isActive AND p.deletedAt IS NULL")
    List<Product> findAllByBusinessIdAndIsActive(@Param("businessId") Long businessId, @Param("isActive") Boolean isActive);

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.business.id = :businessId AND p.sku = :sku AND p.deletedAt IS NULL")
    Optional<Product> findByBusinessIdAndSkuWithDetails(@Param("businessId") Long businessId, @Param("sku") String sku);

    // Dùng sau Specification query để batch load category/unit, tránh N+1
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit WHERE p.id IN :ids")
    List<Product> findAllWithCategoryAndUnit(@Param("ids") List<Long> ids);

    // Scoped theo business để chống IDOR (xem Javadoc findByBusinessIdAndPublicId).
    // Fetch kèm category/unit (toResponse cần) và ph.changedBy — không fetch changedBy thì
    // mỗi dòng price history lazy-load 1 user riêng (N+1) khi build detail response.
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category JOIN FETCH p.unit LEFT JOIN FETCH p.priceHistories ph LEFT JOIN FETCH ph.changedBy WHERE p.business.id = :businessId AND p.publicId = :publicId AND p.deletedAt IS NULL")
    Optional<Product> findByBusinessIdAndPublicIdWithPriceHistories(@Param("businessId") Long businessId, @Param("publicId") UUID publicId);

    @Modifying
    @Query(value = "UPDATE products SET total_stock = (SELECT COALESCE(SUM(quantity), 0) FROM inventory WHERE product_id = :productId AND deleted_at IS NULL) WHERE id = :productId", nativeQuery = true)
    void recalculateTotalStock(@Param("productId") Long productId);
}
