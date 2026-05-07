package com.omniflow.backend.dto.response.order;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
    Long id,
    UUID publicId,
    UUID productPublicId,
    String productName,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal discount,
    String discountType,
    BigDecimal totalPrice,
    Long syncVersion
) {
}

