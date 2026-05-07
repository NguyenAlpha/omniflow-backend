package com.omniflow.backend.dto.request.store;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StoreMemberUpsertRequest(
    @NotNull Long userId,
    @NotBlank @Size(max = 20) String role,
    @Size(max = 100) String positionTitle,
    @NotNull Boolean isActive
) {
}

