package com.omniflow.backend.repository;

import com.omniflow.backend.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    // Global roles (store IS NULL) — used by UserDetailsService to load Spring Security authorities
    List<UserRole> findByUserIdAndStoreIsNullAndDeletedAtIsNull(Long userId);

    // Store-scoped role — used for access control checks
    Optional<UserRole> findByUserIdAndStoreIdAndIsActiveTrueAndDeletedAtIsNull(Long userId, Long storeId);

    // All store-scoped roles for a user — used in auth response
    List<UserRole> findByUserIdAndStoreIsNotNullAndDeletedAtIsNull(Long userId);

    // All soft-deleted roles for a user in a store — used when removing a member
    List<UserRole> findByUserIdAndStoreIdAndDeletedAtIsNull(Long userId, Long storeId);

    // All active roles in a store — used for member listing
    List<UserRole> findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(Long storeId);

    // Used by SystemAdminSeeder to check if user already has a global role
    boolean existsByUserIdAndStoreIsNullAndDeletedAtIsNull(Long userId);
}
