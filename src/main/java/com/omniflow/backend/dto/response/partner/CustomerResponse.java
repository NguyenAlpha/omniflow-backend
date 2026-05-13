package com.omniflow.backend.dto.response.partner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String code,
    String name,
    String phone,
    String email,
    String address,
    BigDecimal debtBalance,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt,
    Instant updatedAt
) {
}

