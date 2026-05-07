package com.omniflow.backend.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnitUpsertRequest(
    @NotBlank @Size(max = 50) String name,
    @NotBlank @Size(max = 10) String abbreviation
) {
}

