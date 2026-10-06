package com.quiktech.pos.dto.request.user;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SetUserStatusRequest(
    @NotNull Boolean isActive,
    @Size(max = 500) String reason
) {
    public SetUserStatusRequest(Boolean isActive) {
        this(isActive, null);
    }
}
