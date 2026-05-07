package com.omniflow.backend.dto.response.catalog;

import java.time.LocalDateTime;
import java.util.UUID;

public record UnitResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String name,
    String abbreviation,
    Long syncVersion,
    LocalDateTime lastModifiedAt
) {
}

