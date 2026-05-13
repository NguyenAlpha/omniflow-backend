package com.omniflow.backend.dto.response.catalog;

import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String name,
    String description,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt,
    Instant updatedAt
) {
}

