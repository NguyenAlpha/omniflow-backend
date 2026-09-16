package com.quiktech.pos.dto.request.purchase;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PurchaseOrderPayRequest(
    @NotNull @DecimalMin("0.01") BigDecimal amount
) {
}
