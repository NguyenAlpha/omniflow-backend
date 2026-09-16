package com.quiktech.pos.dto.request.purchase;

import com.quiktech.pos.entity.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderCreateRequest(
    @NotNull UUID supplierPublicId,
    @NotNull UUID warehousePublicId,
    @DecimalMin("0.00") BigDecimal paidAmount,
    PaymentMethod paymentMethod,
    String note,
    @NotEmpty List<@Valid PurchaseOrderItemRequest> items
) {
}

