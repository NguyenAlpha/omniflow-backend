package com.quiktech.backend.dto.request.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record ReturnOrderItemRequest(
    @NotNull UUID productPublicId,
    @NotNull @DecimalMin(value = "0.01") BigDecimal quantity
) {
}
