package com.omniflow.backend.dto.request.store;

import jakarta.validation.constraints.NotNull;

public record SetStoreStatusRequest(
    @NotNull Boolean isActive
) {
}
