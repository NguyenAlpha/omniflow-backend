package com.quiktech.backend.repository;

import com.quiktech.backend.entity.BusinessMember;
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
