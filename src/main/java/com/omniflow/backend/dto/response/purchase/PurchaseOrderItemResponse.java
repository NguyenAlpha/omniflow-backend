package com.omniflow.backend.dto.response.purchase;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseOrderItemResponse(
    Long id,
    UUID productPublicId,
    String productName,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal totalPrice
) {
}

