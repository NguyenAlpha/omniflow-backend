package com.quiktech.pos.repository;

import com.quiktech.pos.entity.Unit;
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

    // Lookup system unit theo tên (business IS NULL) — dùng cho import CSV,
    // trước đây import chỉ tra business unit nên không thể dùng "Cái", "Kg", ...
    Optional<Unit> findByBusinessIdIsNullAndNameAndDeletedAtIsNull(String name);

    /**
     * Lookup scoped theo business để chống IDOR, nhưng vẫn cho phép truy cập unit hệ thống
     * (business IS NULL — dùng chung cho mọi business). Unit thuộc business khác sẽ trả về
     * empty → 404. Caller vẫn phải tự chặn sửa/xóa unit hệ thống (check business == null).
     * (Filter deleted_at đã có sẵn qua @SQLRestriction trên entity.)
     */
    @Query("""
        SELECT u FROM Unit u
        WHERE (u.business.id = :businessId OR u.business IS NULL)
        AND u.publicId = :publicId
    """)
    Optional<Unit> findByBusinessIdOrSystemAndPublicId(@Param("businessId") Long businessId, @Param("publicId") UUID publicId);

    // Get both system and business units
    @Query("""
        SELECT u FROM Unit u
        WHERE (u.business.id = :businessId OR u.business IS NULL)
        AND u.deletedAt IS NULL
        ORDER BY u.name ASC
    """)
    List<Unit> findSystemAndBusinessUnits(@Param("businessId") Long businessId);
}
