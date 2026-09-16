package com.quiktech.pos.dto.response.subscription;

import com.quiktech.pos.entity.enums.BillingCycle;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;

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
