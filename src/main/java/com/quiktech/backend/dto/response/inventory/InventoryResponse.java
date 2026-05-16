package com.quiktech.backend.dto.response.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InventoryResponse(
    Long id,
    UUID publicId,
    Long storeId,
    UUID productPublicId,
    String productName,
    UUID warehousePublicId,
    String warehouseName,
    BigDecimal quantity,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant updatedAt
) {
}
