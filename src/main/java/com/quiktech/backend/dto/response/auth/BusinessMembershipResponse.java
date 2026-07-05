package com.quiktech.backend.dto.response.auth;

import java.util.List;

public record BusinessMembershipResponse(
    Long businessId,
    String businessName,
    List<StoreInfo> stores
) {
}
