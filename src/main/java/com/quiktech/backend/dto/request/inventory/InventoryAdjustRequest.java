package com.quiktech.backend.dto.request.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record InventoryAdjustRequest(
    UUID productPublicId,
    UUID warehousePublicId,
    BigDecimal quantity,
    String note
) {
}
