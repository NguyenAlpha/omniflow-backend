package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Supplier;
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
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByBusinessIdAndDeletedAtIsNull(Long businessId);

    Optional<Supplier> findByBusinessIdAndCodeAndDeletedAtIsNull(Long businessId, String code);

    Optional<Supplier> findByPublicId(UUID publicId);

    /**
     * Lookup scoped theo business để chống IDOR: {@code @PreAuthorize} chỉ kiểm tra quyền
     * trên businessId của URL, không kiểm tra tenant của entity được load. Supplier không
     * thuộc business trong URL sẽ trả về empty → 404.
     * (Filter deleted_at đã có sẵn qua @SQLRestriction trên entity.)
     */
    Optional<Supplier> findByBusinessIdAndPublicId(Long businessId, UUID publicId);

    // Suppliers with debt
    @Query("""
        SELECT s FROM Supplier s
        WHERE s.business.id = :businessId
        AND s.debtBalance > 0
        AND s.deletedAt IS NULL
        ORDER BY s.debtBalance DESC
    """)
    List<Supplier> findSuppliersWithDebt(@Param("businessId") Long businessId);

    // Search suppliers
    @Query("""
        SELECT s FROM Supplier s
        WHERE s.business.id = :businessId
        AND s.deletedAt IS NULL
        AND (
            s.name ILIKE CONCAT('%', :searchTerm, '%')
            OR s.code ILIKE CONCAT('%', :searchTerm, '%')
            OR s.phone ILIKE CONCAT('%', :searchTerm, '%')
            OR s.email ILIKE CONCAT('%', :searchTerm, '%')
        )
        ORDER BY s.name ASC
    """)
    Page<Supplier> searchSuppliers(
        @Param("businessId") Long businessId,
        @Param("searchTerm") String searchTerm,
        Pageable pageable
    );

    long countByBusinessIdAndDeletedAtIsNull(Long businessId);
}
