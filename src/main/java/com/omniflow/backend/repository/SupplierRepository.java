package com.omniflow.backend.repository;

import com.omniflow.backend.entity.Supplier;
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

    List<Supplier> findByStoreIdAndDeletedAtIsNull(Long storeId);

    Optional<Supplier> findByStoreIdAndCodeAndDeletedAtIsNull(Long storeId, String code);

    Optional<Supplier> findByPublicId(UUID publicId);

    // Suppliers with debt
    @Query("""
        SELECT s FROM Supplier s 
        WHERE s.store.id = :storeId 
        AND s.debtBalance > 0 
        AND s.deletedAt IS NULL
        ORDER BY s.debtBalance DESC
    """)
    List<Supplier> findSuppliersWithDebt(@Param("storeId") Long storeId);

    // Search suppliers
    @Query("""
        SELECT s FROM Supplier s 
        WHERE s.store.id = :storeId 
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
        @Param("storeId") Long storeId,
        @Param("searchTerm") String searchTerm,
        Pageable pageable
    );

    long countByStoreIdAndDeletedAtIsNull(Long storeId);
}

