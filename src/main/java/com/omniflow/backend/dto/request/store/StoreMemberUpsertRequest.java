package com.omniflow.backend.dto.request.store;

import com.omniflow.backend.entity.enums.StoreRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StoreMemberUpsertRequest(
    @NotNull Long userId,
    @NotNull StoreRole role,
    @Size(max = 100) String positionTitle,
    @NotNull Boolean isActive
) {
}
