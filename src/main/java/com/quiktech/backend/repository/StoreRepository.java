package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findByBusinessIdAndDeletedAtIsNull(Long businessId);

    Optional<Store> findByIdAndDeletedAtIsNull(Long id);

    long countByBusinessIdAndDeletedAtIsNull(Long businessId);

    // Scalar query — tránh lazy load khi chỉ cần businessId (dùng trong StoreAccessEvaluator)
    @Query("SELECT s.business.id FROM Store s WHERE s.id = :storeId")
    Optional<Long> findBusinessIdByStoreId(@Param("storeId") Long storeId);

    Optional<Store> findByName(String name);

    Optional<Store> findByNameAndDeletedAtIsNull(String name);

    List<Store> findByIsActiveAndDeletedAtIsNull(Boolean isActive);
}
