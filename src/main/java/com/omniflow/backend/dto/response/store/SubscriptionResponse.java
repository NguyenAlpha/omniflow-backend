package com.omniflow.backend.dto.response.store;

import java.time.Instant;

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
    Instant startedAt,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt
) {
}
