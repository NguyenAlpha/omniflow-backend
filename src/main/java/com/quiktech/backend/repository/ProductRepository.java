package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByStoreIdAndSkuAndDeletedAtIsNull(Long storeId, String sku);

    Optional<Product> findByPublicId(UUID publicId);

    List<Product> findByCategoryIdAndDeletedAtIsNull(Long categoryId);

    long countByStoreIdAndDeletedAtIsNull(Long storeId);

    // ── With totalStock (correlated subquery — 1 query, no N+1) ──────────────

    @Query("""
        SELECT p, (SELECT SUM(i.quantity) FROM Inventory i
                   WHERE i.product.id = p.id AND i.deletedAt IS NULL)
        FROM Product p WHERE p.store.id = :storeId AND p.deletedAt IS NULL
        """)
    List<Object[]> findByStoreIdWithStock(@Param("storeId") Long storeId);

    @Query("""
        SELECT p, (SELECT SUM(i.quantity) FROM Inventory i
                   WHERE i.product.id = p.id AND i.deletedAt IS NULL)
        FROM Product p WHERE p.store.id = :storeId AND p.isActive = :isActive AND p.deletedAt IS NULL
        """)
    List<Object[]> findByStoreIdAndIsActiveWithStock(@Param("storeId") Long storeId, @Param("isActive") Boolean isActive);

    @Query("""
        SELECT p, (SELECT SUM(i.quantity) FROM Inventory i
                   WHERE i.product.id = p.id AND i.deletedAt IS NULL)
        FROM Product p WHERE p.publicId = :publicId AND p.deletedAt IS NULL
        """)
    Optional<Object[]> findByPublicIdWithStock(@Param("publicId") UUID publicId);

    @Query(
        value = """
            SELECT p, (SELECT SUM(i.quantity) FROM Inventory i
                       WHERE i.product.id = p.id AND i.deletedAt IS NULL)
            FROM Product p WHERE p.store.id = :storeId AND p.deletedAt IS NULL
            AND (p.name ILIKE CONCAT('%', :searchTerm, '%')
                 OR p.sku ILIKE CONCAT('%', :searchTerm, '%')
                 OR p.description ILIKE CONCAT('%', :searchTerm, '%'))
            """,
        countQuery = """
            SELECT COUNT(p) FROM Product p WHERE p.store.id = :storeId AND p.deletedAt IS NULL
            AND (p.name ILIKE CONCAT('%', :searchTerm, '%')
                 OR p.sku ILIKE CONCAT('%', :searchTerm, '%')
                 OR p.description ILIKE CONCAT('%', :searchTerm, '%'))
            """
    )
    Page<Object[]> searchProductsWithStock(
        @Param("storeId") Long storeId,
        @Param("searchTerm") String searchTerm,
        Pageable pageable
    );

    @Query("SELECT SUM(i.quantity) FROM Inventory i WHERE i.product.id = :productId AND i.deletedAt IS NULL")
    BigDecimal sumStockByProductId(@Param("productId") Long productId);

    // Using PostgreSQL full-text search
    @Query(value = """
        SELECT * FROM products p
        WHERE p.store_id = :storeId
        AND p.deleted_at IS NULL
        AND to_tsvector('simple', COALESCE(p.name, '') || ' ' || COALESCE(p.sku, '')) @@
            plainto_tsquery('simple', :searchTerm)
        ORDER BY p.name ASC
    """, nativeQuery = true)
    List<Product> fullTextSearch(
        @Param("storeId") Long storeId,
        @Param("searchTerm") String searchTerm
    );
}
