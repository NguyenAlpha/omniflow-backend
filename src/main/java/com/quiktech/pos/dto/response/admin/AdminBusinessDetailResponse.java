package com.quiktech.pos.dto.response.admin;

import com.quiktech.pos.dto.response.business.BusinessResponse;
import com.quiktech.pos.dto.response.store.StoreResponse;
import com.quiktech.pos.dto.response.subscription.SubscriptionResponse;
import java.util.List;

public record AdminBusinessDetailResponse(
        BusinessResponse business,
        SubscriptionResponse subscription,
        List<StoreResponse> stores
) {}
