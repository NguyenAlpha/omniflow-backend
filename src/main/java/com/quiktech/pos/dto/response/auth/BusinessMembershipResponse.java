package com.quiktech.pos.dto.response.auth;

import java.util.List;

public record BusinessMembershipResponse(
    Long businessId,
    String businessName,
    List<StoreInfo> stores
) {
}
