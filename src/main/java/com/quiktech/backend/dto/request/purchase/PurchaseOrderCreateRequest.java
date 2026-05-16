package com.quiktech.backend.dto.request.purchase;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderCreateRequest(
    @NotBlank String orderCode,
    @NotNull UUID supplierPublicId,
    @NotNull UUID warehousePublicId,
    String note,
    @NotEmpty List<@Valid PurchaseOrderItemRequest> items
) {
}

