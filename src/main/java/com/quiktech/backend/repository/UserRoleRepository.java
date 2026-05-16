package com.quiktech.backend.repository;

import com.quiktech.backend.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    // Global roles (store IS NULL) — used by UserDetailsService to load Spring Security authorities
    List<UserRole> findByUserIdAndStoreIsNullAndDeletedAtIsNull(Long userId);

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

    // Active store-scoped roles with role+store eagerly fetched — used in auth response (no lazy N+1)
    @Query("""
        SELECT ur FROM UserRole ur
        JOIN FETCH ur.role
        JOIN FETCH ur.store
        WHERE ur.user.id = :userId
          AND ur.store IS NOT NULL
          AND ur.isActive = true
          AND ur.deletedAt IS NULL
        """)
    List<UserRole> findActiveStoreRolesWithDetails(@Param("userId") Long userId);

    // All soft-deleted roles for a user in a store — used when removing a member
    List<UserRole> findByUserIdAndStoreIdAndDeletedAtIsNull(Long userId, Long storeId);

    // All active roles in a store — used for member listing
    List<UserRole> findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(Long storeId);

    // Used by SystemAdminSeeder to check if user already has a global role
    boolean existsByUserIdAndStoreIsNullAndDeletedAtIsNull(Long userId);
}
