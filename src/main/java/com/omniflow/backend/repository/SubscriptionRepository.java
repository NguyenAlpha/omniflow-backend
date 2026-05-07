package com.omniflow.backend.repository;

import com.omniflow.backend.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByStoreId(Long storeId);

    long countByPlanAndStatus(String plan, String status);
}

