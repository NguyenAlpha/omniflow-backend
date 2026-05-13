package com.omniflow.backend.dto.response.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReturnOrderResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String returnCode,
    UUID originalOrderPublicId,
    UUID warehousePublicId,
    String status,
    String reason,
    BigDecimal totalRefund,
    String refundMethod,
    String note,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt,
    Instant updatedAt,
    List<ReturnOrderItemResponse> items
) {
}
