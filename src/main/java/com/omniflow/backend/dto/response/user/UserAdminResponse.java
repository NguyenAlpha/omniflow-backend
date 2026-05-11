package com.omniflow.backend.dto.response.user;

import java.time.LocalDateTime;

public record UserAdminResponse(
    Long id,
    String username,
    String email,
    String fullName,
    String phone,
    Boolean isActive,
    LocalDateTime createdAt,
    LocalDateTime deletedAt
) {
}
