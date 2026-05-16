package com.quiktech.backend.dto.request.common;

import jakarta.validation.constraints.NotNull;

public record SetStatusRequest(
    @NotNull Boolean isActive
) {
}
