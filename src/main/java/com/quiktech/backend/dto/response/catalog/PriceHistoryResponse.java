package com.quiktech.backend.dto.response.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PriceHistoryResponse(
    Long id,
    Long storeId,
    UUID productPublicId,
    String productName,
    BigDecimal oldCostPrice,
    BigDecimal newCostPrice,
    BigDecimal oldSellingPrice,
    BigDecimal newSellingPrice,
    String changedByUsername,
    Instant changedAt
) {
}
