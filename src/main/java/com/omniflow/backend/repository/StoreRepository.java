package com.omniflow.backend.repository;

import com.omniflow.backend.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByName(String name);

    Optional<Store> findByNameAndDeletedAtIsNull(String name);

    List<Store> findByIsActiveAndDeletedAtIsNull(Boolean isActive);
}

