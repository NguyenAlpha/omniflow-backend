package com.quiktech.backend.dto.request.purchase;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderItemRequest(
    @NotNull UUID productPublicId,
    @NotNull @DecimalMin("0.01") BigDecimal quantity,
    @NotNull @DecimalMin("0.00") BigDecimal unitPrice
) {
}

