package com.omniflow.backend.dto.response.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    LocalDateTime lastModifiedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<ReturnOrderItemResponse> items
) {
}
