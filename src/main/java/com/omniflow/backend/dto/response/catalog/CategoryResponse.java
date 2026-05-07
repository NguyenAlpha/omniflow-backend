package com.omniflow.backend.dto.response.catalog;

import java.time.LocalDateTime;
import java.util.UUID;

public record CategoryResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String name,
    String description,
    Long syncVersion,
    LocalDateTime lastModifiedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}

