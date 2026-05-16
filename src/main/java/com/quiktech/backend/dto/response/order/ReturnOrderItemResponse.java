package com.quiktech.backend.dto.response.order;

import java.math.BigDecimal;
import java.util.UUID;

public record ReturnOrderItemResponse(
    Long id,
    UUID productPublicId,
    String productName,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal totalRefund
) {
}
