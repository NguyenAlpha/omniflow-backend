package com.omniflow.backend.repository;

import com.omniflow.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameAndDeletedAtIsNull(String username);

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByUsernameOrEmail(String username, String email);

    Page<User> findByDeletedAtIsNull(Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.deletedAt IS NULL AND " +
           "(LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           " LOWER(u.email)    LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           " LOWER(u.fullName) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<User> searchUsers(@Param("q") String q, Pageable pageable);
}

