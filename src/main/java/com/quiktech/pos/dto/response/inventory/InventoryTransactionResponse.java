package com.quiktech.pos.dto.response.inventory;

import java.math.BigDecimal;
import java.time.Instant;
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
    BigDecimal previousQuantity,
    UUID orderPublicId,
    UUID purchaseOrderPublicId,
    String note,
    String createdByUsername,
    Instant createdAt
) {
}
