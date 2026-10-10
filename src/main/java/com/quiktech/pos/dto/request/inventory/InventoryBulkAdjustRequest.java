package com.quiktech.pos.dto.request.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Điều chỉnh nhiều product trong cùng một kho. {@code quantity} của từng dòng là delta có dấu
 * (giống {@link InventoryAdjustRequest}); {@code note} áp dụng cho cả lô.
 */
public record InventoryBulkAdjustRequest(
    @NotNull UUID warehousePublicId,
    @NotEmpty @Size(max = 200) List<@Valid @NotNull Item> items,
    String note
) {
    public record Item(
        @NotNull UUID productPublicId,
        @NotNull BigDecimal quantity
    ) {
    }
}
