package com.omniflow.backend.dto.response.purchase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String orderCode,
    UUID supplierPublicId,
    UUID warehousePublicId,
    String status,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    BigDecimal debtAmount,
    String note,
    Long syncVersion,
    LocalDateTime lastModifiedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<PurchaseOrderItemResponse> items
) {
}

