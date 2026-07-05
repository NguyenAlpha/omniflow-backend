package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Customer;
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
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    List<Customer> findByBusinessIdAndDeletedAtIsNull(Long businessId);

    Optional<Customer> findByBusinessIdAndCodeAndDeletedAtIsNull(Long businessId, String code);

    Optional<Customer> findByPublicId(UUID publicId);

    // Customers with debt
    @Query("""
        SELECT c FROM Customer c
        WHERE c.business.id = :businessId
        AND c.debtBalance > 0
        AND c.deletedAt IS NULL
        ORDER BY c.debtBalance DESC
    """)
    List<Customer> findCustomersWithDebt(@Param("businessId") Long businessId);

    // Full-text search
    @Query("""
        SELECT c FROM Customer c
        WHERE c.business.id = :businessId
        AND c.deletedAt IS NULL
        AND (
            c.name ILIKE CONCAT('%', :searchTerm, '%')
            OR c.code ILIKE CONCAT('%', :searchTerm, '%')
            OR c.phone ILIKE CONCAT('%', :searchTerm, '%')
            OR c.email ILIKE CONCAT('%', :searchTerm, '%')
        )
        ORDER BY c.name ASC
    """)
    Page<Customer> searchCustomers(
        @Param("businessId") Long businessId,
        @Param("searchTerm") String searchTerm,
        Pageable pageable
    );

    long countByBusinessIdAndDeletedAtIsNull(Long businessId);
}
