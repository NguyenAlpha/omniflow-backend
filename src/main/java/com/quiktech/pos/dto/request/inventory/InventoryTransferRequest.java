package com.quiktech.pos.dto.request.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record InventoryTransferRequest(
    @NotNull UUID productPublicId,
    @NotNull UUID fromWarehousePublicId,
    @NotNull UUID toWarehousePublicId,
    @NotNull @DecimalMin("0.01") BigDecimal quantity,
    String note
) {
}
