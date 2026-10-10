package com.quiktech.pos.dto.request.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Chuyển nhiều product giữa hai kho. {@code quantity} của từng dòng là số dương
 * (giống {@link InventoryTransferRequest}); {@code note} áp dụng cho cả lô.
 */
public record InventoryBulkTransferRequest(
    @NotNull UUID fromWarehousePublicId,
    @NotNull UUID toWarehousePublicId,
    @NotEmpty @Size(max = 200) List<@Valid @NotNull Item> items,
    String note
) {
    public record Item(
        @NotNull UUID productPublicId,
        @NotNull @DecimalMin("0.01") BigDecimal quantity
    ) {
    }
}
