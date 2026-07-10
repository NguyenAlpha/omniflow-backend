package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Customer;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Lookup scoped theo business để chống IDOR: {@code @PreAuthorize} chỉ kiểm tra quyền
     * trên businessId của URL, không kiểm tra tenant của entity được load. Customer không
     * thuộc business trong URL sẽ trả về empty → 404.
     * (Filter deleted_at đã có sẵn qua @SQLRestriction trên entity.)
     */
    Optional<Customer> findByBusinessIdAndPublicId(Long businessId, UUID publicId);

    /**
     * Biến thể có SELECT ... FOR UPDATE cho nghiệp vụ tiền bạc (pay công nợ):
     * serialize chuỗi đọc debtBalance → trừ nợ → phân bổ order → tạo Payment.
     * Nếu không lock, 2 request pay đồng thời cùng đọc một debtBalance
     * → double-payment. Lock giữ đến khi transaction của caller commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Customer c WHERE c.business.id = :businessId AND c.publicId = :publicId")
    Optional<Customer> findByBusinessIdAndPublicIdForUpdate(@Param("businessId") Long businessId, @Param("publicId") UUID publicId);

    // Customers with debt
    @Query("""
        SELECT c FROM Customer c
        WHERE c.business.id = :businessId
        AND c.debtBalance > 0
        AND c.deletedAt IS NULL
        ORDER BY c.debtBalance DESC
    """)
    List<Customer> findCustomersWithDebt(@Param("businessId") Long businessId);

    // Substring search bằng ILIKE (không phải full-text search — search_vector + GIN index
    // trong schema chưa được dùng ở đây). ESCAPE '\': caller (CustomerService) escape %/_
    // trong searchTerm để wildcard người dùng gõ vào không match toàn bộ bảng.
    @Query("""
        SELECT c FROM Customer c
        WHERE c.business.id = :businessId
        AND c.deletedAt IS NULL
        AND (
            c.name ILIKE CONCAT('%', :searchTerm, '%') ESCAPE '\\'
            OR c.code ILIKE CONCAT('%', :searchTerm, '%') ESCAPE '\\'
            OR c.phone ILIKE CONCAT('%', :searchTerm, '%') ESCAPE '\\'
            OR c.email ILIKE CONCAT('%', :searchTerm, '%') ESCAPE '\\'
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
