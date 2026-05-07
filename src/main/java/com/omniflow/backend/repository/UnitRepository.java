package com.omniflow.backend.repository;

import com.omniflow.backend.entity.Unit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UnitRepository extends JpaRepository<Unit, Long> {
    
    // System units
    List<Unit> findByStoreIdIsNullAndDeletedAtIsNull();
    
    // Store-specific units
    List<Unit> findByStoreIdAndDeletedAtIsNull(Long storeId);
    
    Optional<Unit> findByStoreIdAndNameAndDeletedAtIsNull(Long storeId, String name);
    
    Optional<Unit> findByPublicId(UUID publicId);
    
    // Get both system and store units
    @Query("""
        SELECT u FROM Unit u 
        WHERE (u.store.id = :storeId OR u.store IS NULL) 
        AND u.deletedAt IS NULL
        ORDER BY u.name ASC
    """)
    List<Unit> findSystemAndStoreUnits(@Param("storeId") Long storeId);
}

