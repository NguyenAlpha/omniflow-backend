package com.quiktech.backend.repository;

import com.quiktech.backend.entity.StoreMember;
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

    /**
     * Load member scoped theo storeId — chống IDOR: memberId là số tự tăng đoán được,
     * nếu chỉ findById(memberId) thì OWNER của business A có thể sửa/xóa member của
     * business B qua URL /api/stores/{storeIdCủaA}/members/{memberIdCủaB}
     * (@PreAuthorize chỉ xác nhận quyền trên store trong URL, không liên quan store của member).
     */
    Optional<StoreMember> findByIdAndStoreIdAndDeletedAtIsNull(Long id, Long storeId);

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

    @Query("SELECT COUNT(sm) FROM StoreMember sm WHERE sm.store.business.id = :businessId AND sm.deletedAt IS NULL")
    long countByBusinessId(@Param("businessId") Long businessId);
}

