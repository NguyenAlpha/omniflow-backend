package com.quiktech.backend.repository;

import com.quiktech.backend.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    // Global roles only (business IS NULL AND store IS NULL) — SUPER_ADMIN, SUPPORT
    List<UserRole> findByUserIdAndBusinessIsNullAndStoreIsNullAndDeletedAtIsNull(Long userId);

    // Business-scoped role (OWNER)
    @Query("""
        SELECT ur FROM UserRole ur
        JOIN FETCH ur.role
        WHERE ur.user.id = :userId
          AND ur.business.id = :businessId
          AND ur.store IS NULL
          AND ur.isActive = true
          AND ur.deletedAt IS NULL
        """)
    Optional<UserRole> findActiveBusinessRole(@Param("userId") Long userId, @Param("businessId") Long businessId);

    // Store-scoped role with role eagerly fetched — used for access control checks (no lazy N+1)
    @Query("""
        SELECT ur FROM UserRole ur
        JOIN FETCH ur.role
        WHERE ur.user.id = :userId
          AND ur.store.id = :storeId
          AND ur.isActive = true
          AND ur.deletedAt IS NULL
        """)
    Optional<UserRole> findActiveStoreRole(@Param("userId") Long userId, @Param("storeId") Long storeId);

    // Store-scoped roles with role+store+business eagerly fetched — used in auth response
    @Query("""
        SELECT ur FROM UserRole ur
        JOIN FETCH ur.role
        JOIN FETCH ur.store s
        JOIN FETCH s.business
        WHERE ur.user.id = :userId
          AND ur.store IS NOT NULL
          AND ur.isActive = true
          AND ur.deletedAt IS NULL
        """)
    List<UserRole> findActiveStoreRolesWithBusinessDetails(@Param("userId") Long userId);

    // All active roles for a user in a store — used when removing a member
    List<UserRole> findByUserIdAndStoreIdAndDeletedAtIsNull(Long userId, Long storeId);

    // All active roles in a store — used for member listing
    List<UserRole> findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(Long storeId);

    // All active roles in a business — used for business member listing
    List<UserRole> findByBusinessIdAndIsActiveTrueAndDeletedAtIsNull(Long businessId);

    // All active business-level roles for a user — used in getStores() and auth response
    @Query("""
        SELECT ur FROM UserRole ur
        JOIN FETCH ur.role
        JOIN FETCH ur.business
        WHERE ur.user.id = :userId
          AND ur.business IS NOT NULL
          AND ur.isActive = true
          AND ur.deletedAt IS NULL
        """)
    List<UserRole> findActiveBusinessRolesForUser(@Param("userId") Long userId);

    // Store roles in a specific business — used by BusinessAccessEvaluator
    @Query("""
        SELECT ur FROM UserRole ur
        JOIN FETCH ur.role
        WHERE ur.user.id = :userId
          AND ur.store.business.id = :businessId
          AND ur.isActive = true
          AND ur.deletedAt IS NULL
        """)
    List<UserRole> findActiveStoreRolesInBusiness(@Param("userId") Long userId, @Param("businessId") Long businessId);

    // Used by SystemAdminSeeder to check if user already has a global role
    boolean existsByUserIdAndBusinessIsNullAndStoreIsNullAndDeletedAtIsNull(Long userId);

    // Soft-delete toàn bộ role của user — gọi khi xóa mềm user để evaluator không còn
    // thấy role active (findActiveStoreRole/findActiveBusinessRole filter deletedAt IS NULL)
    @Modifying
    @Query("UPDATE UserRole ur SET ur.deletedAt = :now WHERE ur.user.id = :userId AND ur.deletedAt IS NULL")
    int softDeleteAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}
