package com.quiktech.backend.dto.response.auth;

import com.quiktech.backend.dto.response.store.StoreMemberResponse;
import java.util.List;

public record AuthResponse(
    String accessToken,
    String tokenType,
    Long expiresIn,
    UserSummaryResponse user,
    List<StoreMemberResponse> storeMemberships
) {
}

