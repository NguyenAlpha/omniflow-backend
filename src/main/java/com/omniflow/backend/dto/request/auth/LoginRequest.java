package com.omniflow.backend.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(
    @NotBlank String usernameOrEmail,
    @NotBlank String password,
    @NotNull Long storeId
) {
}

