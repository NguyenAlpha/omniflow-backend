package com.omniflow.backend.dto.response.catalog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    LocalDateTime changedAt
) {
}
