package com.omniflow.backend.dto.response.warehouse;

import java.time.Instant;
import java.util.UUID;

public record WarehouseResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String name,
    String address,
    Boolean isActive,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt,
    Instant updatedAt
) {
}
