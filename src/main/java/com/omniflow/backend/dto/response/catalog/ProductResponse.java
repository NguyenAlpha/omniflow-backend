package com.omniflow.backend.dto.response.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String sku,
    String name,
    String description,
    Long categoryId,
    Long unitId,
    BigDecimal costPrice,
    BigDecimal sellingPrice,
    BigDecimal totalStock,
    Integer minStockLevel,
    Boolean isActive,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt,
    Instant updatedAt
) {
}
