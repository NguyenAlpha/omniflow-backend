package com.quiktech.pos.dto.request.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryUpsertRequest(
    @NotBlank @Size(max = 30) String name,
    @Size(max = 100) String description
) {
}

