package com.quiktech.backend.repository;

import com.quiktech.backend.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByStoreId(Long storeId);

    Optional<Category> findByStoreIdAndNameAndDeletedAtIsNull(Long storeId, String name);

    Optional<Category> findByPublicId(UUID publicId);

    long countByStoreIdAndDeletedAtIsNull(Long storeId);
}

