package com.quiktech.backend.dto.response.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    Long id,
    UUID publicId,
    Long storeId,
    UUID customerPublicId,
    UUID supplierPublicId,
    BigDecimal amount,
    String paymentMethod,
    String note,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt
) {
}

