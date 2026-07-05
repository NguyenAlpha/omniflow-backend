package com.quiktech.backend.dto.response.subscription;

import com.quiktech.backend.entity.enums.BillingCycle;
import com.quiktech.backend.entity.enums.SubscriptionPlan;
import com.quiktech.backend.entity.enums.SubscriptionStatus;

import java.time.Instant;

public record SubscriptionResponse(
        Long id,
        Long businessId,
        SubscriptionPlan plan,
        SubscriptionStatus status,
        BillingCycle billingCycle,
        Integer maxStores,
        Integer maxStaff,
        Integer maxProducts,
        Integer maxWarehouses,
        Instant startedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        SubscriptionPlan pendingPlan,
        BillingCycle pendingBillingCycle
) {
}
