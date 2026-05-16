package com.quiktech.backend.dto.request.user;

import jakarta.validation.constraints.NotNull;

public record SetUserStatusRequest(
    @NotNull Boolean isActive
) {
}
