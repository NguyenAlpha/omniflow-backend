package com.quiktech.backend.dto.request.subscription;

import com.quiktech.backend.entity.enums.BillingCycle;
import com.quiktech.backend.entity.enums.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;

public record UpgradeRequest(
        @NotNull SubscriptionPlan plan,
        @NotNull BillingCycle billingCycle
) {
}
