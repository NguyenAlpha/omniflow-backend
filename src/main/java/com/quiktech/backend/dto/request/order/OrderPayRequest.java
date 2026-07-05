package com.quiktech.backend.dto.request.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record OrderPayRequest(
    @NotNull @DecimalMin("0.01") BigDecimal amount
) {
}
