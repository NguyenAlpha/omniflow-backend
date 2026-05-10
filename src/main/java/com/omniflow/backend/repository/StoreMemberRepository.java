package com.omniflow.backend.repository;

import com.omniflow.backend.entity.StoreMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StoreMemberRepository extends JpaRepository<StoreMember, Long> {

    List<StoreMember> findByStoreId(Long storeId);

    List<StoreMember> findByStoreIdAndIsActiveAndDeletedAtIsNull(Long storeId, Boolean isActive);

    List<StoreMember> findByUserId(Long userId);

    List<StoreMember> findByUserIdAndDeletedAtIsNull(Long userId);

    Optional<StoreMember> findByUserIdAndStoreId(Long userId, Long storeId);

    Optional<StoreMember> findByUserIdAndStoreIdAndDeletedAtIsNull(Long userId, Long storeId);

    Optional<StoreMember> findByPublicId(UUID publicId);

    @Query("""
        SELECT sm FROM StoreMember sm
        WHERE sm.store.id = :storeId
          AND sm.user.id = :userId
          AND sm.deletedAt IS NULL
        """)
    Optional<StoreMember> findActiveStoreMember(@Param("storeId") Long storeId, @Param("userId") Long userId);

    // Used in auth response — store eagerly fetched to avoid lazy N+1 on getStore().getId()
    @Query("""
        SELECT sm FROM StoreMember sm
        JOIN FETCH sm.store
        WHERE sm.user.id = :userId
          AND sm.deletedAt IS NULL
        """)
    List<StoreMember> findByUserIdAndDeletedAtIsNullWithStore(@Param("userId") Long userId);
}

