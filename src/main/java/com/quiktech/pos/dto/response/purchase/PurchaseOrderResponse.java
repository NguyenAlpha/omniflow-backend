package com.quiktech.pos.dto.response.purchase;

import com.quiktech.pos.entity.enums.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderResponse(
    Long id,
    UUID publicId,
    Long storeId,
    String orderCode,
    UUID supplierPublicId,
    String supplierName,
    UUID warehousePublicId,
    String warehouseName,
    PurchaseOrderStatus status,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    BigDecimal debtAmount,
    String note,
    Long syncVersion,
    Instant lastModifiedAt,
    Instant createdAt,
    Instant updatedAt,
    List<PurchaseOrderItemResponse> items
) {
}

