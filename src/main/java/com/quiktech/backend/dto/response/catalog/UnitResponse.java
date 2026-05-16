package com.quiktech.backend.dto.response.catalog;

import java.time.Instant;
import java.util.UUID;

public record UnitResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String name,
    String abbreviation,
    Long syncVersion,
    Instant lastModifiedAt
) {
}

