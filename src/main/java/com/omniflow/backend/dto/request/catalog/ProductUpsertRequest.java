package com.omniflow.backend.dto.request.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record ProductUpsertRequest(
    @NotBlank @Size(max = 50) String sku,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 4000) String description,
    UUID categoryPublicId,
    @NotNull UUID unitPublicId,
    @NotNull @DecimalMin("0.00") BigDecimal costPrice,
    @NotNull @DecimalMin("0.00") BigDecimal sellingPrice,
    @NotNull Integer minStockLevel,
    @NotNull Boolean isActive
) {
}

