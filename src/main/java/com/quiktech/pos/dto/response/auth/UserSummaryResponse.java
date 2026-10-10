package com.quiktech.pos.dto.response.auth;

public record UserSummaryResponse(
    Long id,
    String username,
    String email,
    String fullName,
    String phone,
    Boolean isActive
) {
}
