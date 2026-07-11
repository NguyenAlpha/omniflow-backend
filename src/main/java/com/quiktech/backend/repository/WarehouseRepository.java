package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    List<Warehouse> findByStoreIdAndDeletedAtIsNull(Long storeId);

    Optional<Warehouse> findByStoreIdAndNameAndDeletedAtIsNull(Long storeId, String name);

    List<Warehouse> findByStoreIdAndIsActiveAndDeletedAtIsNull(Long storeId, Boolean isActive);

    /**
     * Lookup scoped theo store để chống IDOR: {@code @PreAuthorize} chỉ kiểm tra quyền
     * trên storeId của URL, không kiểm tra tenant của entity được load. Warehouse không
     * thuộc store trong URL sẽ trả về empty → 404.
     */
    Optional<Warehouse> findByPublicIdAndStoreId(UUID publicId, Long storeId);

    long countByStoreIdAndDeletedAtIsNull(Long storeId);

    @Query("SELECT COUNT(w) FROM Warehouse w WHERE w.store.business.id = :businessId AND w.deletedAt IS NULL")
    long countByBusinessId(@Param("businessId") Long businessId);
}

