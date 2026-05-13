package com.omniflow.backend.dto.response.store;

import java.time.Instant;

public record StoreResponse(
    Long id,
    String name,
    String address,
    String phone,
    String email,
    Boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {
}

