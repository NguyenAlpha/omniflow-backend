package com.quiktech.pos.dto.request.inventory;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record InventoryAdjustRequest(
    @NotNull UUID productPublicId,
    @NotNull UUID warehousePublicId,
    @NotNull BigDecimal quantity,
    String note
) {
}
