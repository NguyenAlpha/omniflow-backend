package com.quiktech.backend.dto.request.business;

import jakarta.validation.constraints.NotNull;

public record UpdateBusinessMemberRequest(
    @NotNull Boolean isActive
) {
}
