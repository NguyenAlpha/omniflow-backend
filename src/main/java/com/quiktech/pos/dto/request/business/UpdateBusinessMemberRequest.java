package com.quiktech.pos.dto.request.business;

import jakarta.validation.constraints.NotNull;

public record UpdateBusinessMemberRequest(
    @NotNull Boolean isActive
) {
}
