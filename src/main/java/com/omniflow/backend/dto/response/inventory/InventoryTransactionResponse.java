package com.omniflow.backend.dto.response.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record InventoryTransactionResponse(
    Long id,
    Long storeId,
    UUID productPublicId,
    String productName,
    UUID warehousePublicId,
    String warehouseName,
    String type,
    BigDecimal quantity,
    UUID orderPublicId,
    UUID purchaseOrderPublicId,
    String note,
    String createdByUsername,
    LocalDateTime createdAt
) {
}
