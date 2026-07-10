package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByBusinessId(Long businessId);

    Optional<Category> findByBusinessIdAndNameAndDeletedAtIsNull(Long businessId, String name);

    /**
     * Lookup scoped theo business để chống IDOR: {@code @PreAuthorize} chỉ kiểm tra quyền
     * trên businessId của URL, không kiểm tra tenant của entity được load. Category không
     * thuộc business trong URL sẽ trả về empty → 404.
     * (Filter deleted_at đã có sẵn qua @SQLRestriction trên entity.)
     */
    Optional<Category> findByBusinessIdAndPublicId(Long businessId, UUID publicId);

    long countByBusinessIdAndDeletedAtIsNull(Long businessId);
}

