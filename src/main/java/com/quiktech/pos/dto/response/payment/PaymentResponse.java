package com.quiktech.pos.dto.response.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    Long id,
    UUID publicId,
    Long storeId,
    UUID customerPublicId,
    String customerName,
    UUID supplierPublicId,
    String supplierName,
    BigDecimal amount,
    String paymentMethod,
    String note,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt
) {
}

