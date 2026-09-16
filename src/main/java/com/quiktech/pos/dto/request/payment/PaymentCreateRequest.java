package com.quiktech.pos.dto.request.payment;

import com.quiktech.pos.entity.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentCreateRequest(
    UUID customerPublicId,
    UUID supplierPublicId,
    @NotNull @DecimalMin("0.01") BigDecimal paidAmount,
    @NotNull PaymentMethod paymentMethod,
    String note
) {
}

