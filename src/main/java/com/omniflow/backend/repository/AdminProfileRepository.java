package com.omniflow.backend.repository;

import com.omniflow.backend.entity.AdminProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface AdminProfileRepository extends JpaRepository<AdminProfile, Long> {

    Optional<AdminProfile> findByUserId(Long userId);

    Optional<AdminProfile> findByUserIdAndDeletedAtIsNull(Long userId);
}

