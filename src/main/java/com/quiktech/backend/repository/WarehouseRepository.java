package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    List<Warehouse> findByStoreIdAndDeletedAtIsNull(Long storeId);

    Optional<Warehouse> findByStoreIdAndNameAndDeletedAtIsNull(Long storeId, String name);

    List<Warehouse> findByStoreIdAndIsActiveAndDeletedAtIsNull(Long storeId, Boolean isActive);

    Optional<Warehouse> findByPublicId(UUID publicId);

    long countByStoreIdAndDeletedAtIsNull(Long storeId);
}

