package com.omniflow.backend.dto.request.order;

import java.math.BigDecimal;
import java.util.UUID;

public record ReturnOrderItemRequest(
    UUID productPublicId,
    BigDecimal quantity,
    BigDecimal unitPrice
) {
}
