package com.omniflow.backend.dto.request.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemRequest(
    @NotNull UUID productPublicId,
    @NotNull @DecimalMin("0.01") BigDecimal quantity,
    @NotNull @DecimalMin("0.00") BigDecimal unitPrice,
    @NotNull @DecimalMin("0.00") BigDecimal discount,
    @NotNull String discountType
) {
}

