package com.omniflow.backend.repository;

import com.omniflow.backend.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdAndDeletedAtIsNull(Long orderId);

    Optional<OrderItem> findByPublicId(UUID publicId);

    long countByOrderIdAndDeletedAtIsNull(Long orderId);

    void deleteByOrderId(Long orderId);
}

