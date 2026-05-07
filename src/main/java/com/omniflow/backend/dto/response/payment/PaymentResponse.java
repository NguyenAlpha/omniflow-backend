package com.omniflow.backend.dto.response.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    LocalDateTime lastModifiedAt,
    LocalDateTime createdAt
) {
}

