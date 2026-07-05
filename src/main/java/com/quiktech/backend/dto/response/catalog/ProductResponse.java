package com.quiktech.backend.dto.response.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
    Long id,
    UUID publicId,
    Long businessId,
    String sku,
    String name,
    String description,
    Long categoryId,
    UUID categoryPublicId,
    String categoryName,
    Long unitId,
    UUID unitPublicId,
    String unitName,
    String unitAbbreviation,
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
