package com.quiktech.backend.dto.request.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record OrderCreateRequest(
    @NotBlank String orderCode,
    UUID customerPublicId,
    @NotNull UUID warehousePublicId,
    @NotNull @DecimalMin("0.00") BigDecimal discount,
    @NotNull String discountType,
    @NotNull @DecimalMin("0.00") BigDecimal tax,
    String note,
    @NotEmpty List<@Valid OrderItemRequest> items
) {
}

