package com.omniflow.backend.dto.request.user;

import jakarta.validation.constraints.NotNull;

public record SetUserStatusRequest(
    @NotNull Boolean isActive
) {
}
