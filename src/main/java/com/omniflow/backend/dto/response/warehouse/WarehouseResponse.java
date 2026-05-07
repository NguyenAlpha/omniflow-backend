package com.omniflow.backend.dto.response.warehouse;

import java.time.LocalDateTime;
import java.util.UUID;

public record WarehouseResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String name,
    String address,
    Boolean isActive,
    Long syncVersion,
    LocalDateTime lastModifiedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
