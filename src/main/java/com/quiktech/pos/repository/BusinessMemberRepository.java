package com.quiktech.pos.repository;

import com.quiktech.pos.entity.BusinessMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BusinessMemberRepository extends JpaRepository<BusinessMember, Long> {

    List<BusinessMember> findByBusinessId(Long businessId);

    List<BusinessMember> findByBusinessIdAndIsActiveAndDeletedAtIsNull(Long businessId, Boolean isActive);

    /**
     * Load member scoped theo businessId — chống IDOR: memberId là số tự tăng đoán được,
     * nếu chỉ findById(memberId) thì OWNER của business A có thể sửa/xóa member của
     * business B qua URL /api/businesses/{businessIdCủaA}/members/{memberIdCủaB}.
     */
    Optional<BusinessMember> findByIdAndBusinessIdAndDeletedAtIsNull(Long id, Long businessId);

    @Query("""
        SELECT bm FROM BusinessMember bm
        WHERE bm.business.id = :businessId
          AND bm.user.id = :userId
          AND bm.deletedAt IS NULL
        """)
    Optional<BusinessMember> findActiveBusinessMember(@Param("businessId") Long businessId, @Param("userId") Long userId);

    List<BusinessMember> findByUserId(Long userId);

    List<BusinessMember> findByUserIdAndDeletedAtIsNull(Long userId);

    Optional<BusinessMember> findByUserIdAndBusinessIdAndDeletedAtIsNull(Long userId, Long businessId);

    Optional<BusinessMember> findByPublicId(UUID publicId);

    // Used in auth response — business eagerly fetched to avoid lazy N+1
    @Query("""
        SELECT bm FROM BusinessMember bm
        JOIN FETCH bm.business
        WHERE bm.user.id = :userId
          AND bm.deletedAt IS NULL
        """)
    List<BusinessMember> findByUserIdAndDeletedAtIsNullWithBusiness(@Param("userId") Long userId);
}
