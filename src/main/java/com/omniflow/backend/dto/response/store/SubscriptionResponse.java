package com.omniflow.backend.dto.response.store;

import java.time.LocalDateTime;

public record SubscriptionResponse(
    Long id,
    Long storeId,
    String plan,
    String status,
    String billingCycle,
    Integer maxStaff,
    Integer maxProducts,
    Integer maxWarehouses,
    Integer maxOrdersPerMonth,
    LocalDateTime startedAt,
    LocalDateTime expiresAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
