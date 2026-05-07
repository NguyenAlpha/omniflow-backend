package com.omniflow.backend.dto.response.store;

import java.time.LocalDateTime;

public record StoreResponse(
    Long id,
    String name,
    String address,
    String phone,
    String email,
    Boolean isActive,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}

