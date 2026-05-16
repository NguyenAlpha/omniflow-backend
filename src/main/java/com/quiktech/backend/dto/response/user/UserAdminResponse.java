package com.quiktech.backend.dto.response.user;

import java.time.Instant;

public record UserAdminResponse(
    Long id,
    String username,
    String email,
    String fullName,
    String phone,
    Boolean isActive,
    Instant createdAt,
    Instant deletedAt
) {
}
