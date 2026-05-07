package com.omniflow.backend.dto.response.auth;

import java.util.UUID;

public record UserSummaryResponse(
    Long id,
    UUID publicId,
    String username,
    String email,
    String fullName,
    String phone,
    Boolean isActive
) {
}

