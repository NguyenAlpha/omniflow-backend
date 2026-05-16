package com.quiktech.backend.dto.response.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String orderCode,
    UUID customerPublicId,
    UUID warehousePublicId,
    String status,
    BigDecimal subtotal,
    BigDecimal discount,
    String discountType,
    BigDecimal tax,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    BigDecimal debtAmount,
    String note,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt,
    Instant updatedAt,
    List<OrderItemResponse> items
) {
}

