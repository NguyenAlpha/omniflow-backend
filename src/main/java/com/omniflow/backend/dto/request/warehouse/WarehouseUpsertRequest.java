package com.omniflow.backend.dto.request.warehouse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WarehouseUpsertRequest(
    @NotBlank @Size(max = 100) String name,
    String address,
    Boolean isActive
) {
}
