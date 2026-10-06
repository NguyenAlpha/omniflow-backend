package com.quiktech.pos.dto.request.subscription;

import com.quiktech.pos.entity.enums.BillingCycle;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangePlanRequest(
        @NotNull SubscriptionPlan plan,
        // Bắt buộc khi plan là gói trả phí (validate ở service — dùng để tính expiresAt);
        // plan = FREE thì bỏ trống
        BillingCycle billingCycle,
        @Size(max = 500) String reason
) {
    public ChangePlanRequest(SubscriptionPlan plan, BillingCycle billingCycle) {
        this(plan, billingCycle, null);
    }
}
