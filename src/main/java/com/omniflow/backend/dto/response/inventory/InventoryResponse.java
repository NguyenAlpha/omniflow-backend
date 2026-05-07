package com.omniflow.backend.dto.response.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    LocalDateTime lastModifiedAt,
    LocalDateTime updatedAt
) {
}
