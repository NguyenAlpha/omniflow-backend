package com.quiktech.pos.dto.request.common;

import jakarta.validation.constraints.NotNull;

public record SetStatusRequest(
    @NotNull Boolean isActive
) {
}
