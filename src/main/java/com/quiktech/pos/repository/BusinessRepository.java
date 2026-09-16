package com.quiktech.pos.repository;

import com.quiktech.pos.entity.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {

    Optional<Business> findByNameAndDeletedAtIsNull(String name);

    List<Business> findByIsActiveAndDeletedAtIsNull(Boolean isActive);
}
