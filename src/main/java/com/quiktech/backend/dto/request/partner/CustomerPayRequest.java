package com.quiktech.backend.dto.request.partner;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CustomerPayRequest(
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    String paymentMethod
) {}
