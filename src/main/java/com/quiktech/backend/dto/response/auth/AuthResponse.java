package com.quiktech.backend.dto.response.auth;

import java.util.List;

public record AuthResponse(
    String accessToken,
    String tokenType,
    Long expiresIn,
    UserSummaryResponse user,
    List<BusinessMembershipResponse> memberships,
    String refreshToken
) {
}

