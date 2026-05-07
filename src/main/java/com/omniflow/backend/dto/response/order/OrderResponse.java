package com.omniflow.backend.dto.response.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    LocalDateTime lastModifiedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<OrderItemResponse> items
) {
}

