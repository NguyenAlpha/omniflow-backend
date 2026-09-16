package com.quiktech.pos.repository;

import com.quiktech.pos.entity.Supplier;
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

    /**
     * Biến thể có SELECT ... FOR UPDATE cho nghiệp vụ tiền bạc (pay công nợ):
     * serialize chuỗi đọc debtBalance → trừ nợ → phân bổ purchase order → tạo Payment.
     * Nếu không lock, 2 request pay đồng thời cùng đọc một debtBalance
     * → double-payment. Lock giữ đến khi transaction của caller commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Supplier s WHERE s.business.id = :businessId AND s.publicId = :publicId")
    Optional<Supplier> findByBusinessIdAndPublicIdForUpdate(@Param("businessId") Long businessId, @Param("publicId") UUID publicId);

    // Suppliers with debt
    @Query("""
        SELECT s FROM Supplier s
        WHERE s.business.id = :businessId
        AND s.debtBalance > 0
        AND s.deletedAt IS NULL
        ORDER BY s.debtBalance DESC
    """)
    List<Supplier> findSuppliersWithDebt(@Param("businessId") Long businessId);

    // Search suppliers — substring search bằng ILIKE. ESCAPE '\': caller (SupplierService)
    // escape %/_ trong searchTerm để wildcard người dùng gõ vào không match toàn bộ bảng.
    @Query("""
        SELECT s FROM Supplier s
        WHERE s.business.id = :businessId
        AND s.deletedAt IS NULL
        AND (
            s.name ILIKE CONCAT('%', :searchTerm, '%') ESCAPE '\\'
            OR s.code ILIKE CONCAT('%', :searchTerm, '%') ESCAPE '\\'
            OR s.phone ILIKE CONCAT('%', :searchTerm, '%') ESCAPE '\\'
            OR s.email ILIKE CONCAT('%', :searchTerm, '%') ESCAPE '\\'
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
