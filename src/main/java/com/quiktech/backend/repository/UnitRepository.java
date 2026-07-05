package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Unit;
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
    List<Unit> findByBusinessIdIsNullAndDeletedAtIsNull();

    // Business-specific units
    List<Unit> findByBusinessIdAndDeletedAtIsNull(Long businessId);

    Optional<Unit> findByBusinessIdAndNameAndDeletedAtIsNull(Long businessId, String name);

    Optional<Unit> findByPublicId(UUID publicId);

    // Get both system and business units
    @Query("""
        SELECT u FROM Unit u
        WHERE (u.business.id = :businessId OR u.business IS NULL)
        AND u.deletedAt IS NULL
        ORDER BY u.name ASC
    """)
    List<Unit> findSystemAndBusinessUnits(@Param("businessId") Long businessId);
}
