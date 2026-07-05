package com.quiktech.backend.dto.request.payment;

import com.quiktech.backend.entity.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCreateRequest(
    UUID customerPublicId,
    UUID supplierPublicId,
    @NotNull @DecimalMin("0.01") BigDecimal paidAmount,
    @NotBlank PaymentMethod paymentMethod,
    String note
) {
}

