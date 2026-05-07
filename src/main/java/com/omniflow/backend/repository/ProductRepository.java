package com.omniflow.backend.repository;

import com.omniflow.backend.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByStoreIdAndDeletedAtIsNull(Long storeId);

    Optional<Product> findByStoreIdAndSkuAndDeletedAtIsNull(Long storeId, String sku);

    Optional<Product> findByPublicId(UUID publicId);

    List<Product> findByStoreIdAndIsActiveAndDeletedAtIsNull(Long storeId, Boolean isActive);

    List<Product> findByCategoryIdAndDeletedAtIsNull(Long categoryId);

    long countByStoreIdAndDeletedAtIsNull(Long storeId);

    // Full-text search
    @Query(value = """
        SELECT p FROM Product p 
        WHERE p.store.id = :storeId 
        AND p.deletedAt IS NULL 
        AND (
            p.name ILIKE CONCAT('%', :searchTerm, '%')
            OR p.sku ILIKE CONCAT('%', :searchTerm, '%')
            OR p.description ILIKE CONCAT('%', :searchTerm, '%')
        )
    """)
    Page<Product> searchProducts(
        @Param("storeId") Long storeId,
        @Param("searchTerm") String searchTerm,
        Pageable pageable
    );

    // Using PostgreSQL full-text search
    @Query(value = """
        SELECT p FROM Product p 
        WHERE p.store.id = :storeId 
        AND p.deletedAt IS NULL 
        AND to_tsvector('simple', COALESCE(p.name, '') || ' ' || COALESCE(p.sku, '')) @@ 
            plainto_tsquery('simple', :searchTerm)
        ORDER BY p.name ASC
    """, nativeQuery = false)
    List<Product> fullTextSearch(
        @Param("storeId") Long storeId,
        @Param("searchTerm") String searchTerm
    );
}

