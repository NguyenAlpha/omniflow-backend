package com.omniflow.backend.dto.request.store;

import com.omniflow.backend.entity.enums.RoleName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddMemberRequest(
    @NotNull Long userId,
    @NotNull RoleName role,
    @Size(max = 100) String positionTitle,
    @NotNull Boolean isActive
) {
}
