package com.quiktech.backend.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryUpsertRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 2000) String description
) {
}

