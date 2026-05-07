package com.omniflow.backend.dto.request.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCreateRequest(
    UUID customerPublicId,
    UUID supplierPublicId,
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotBlank String paymentMethod,
    String note
) {
}

