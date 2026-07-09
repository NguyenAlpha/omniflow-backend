package com.quiktech.backend.dto.request.subscription;

import com.quiktech.backend.entity.enums.BillingCycle;
import com.quiktech.backend.entity.enums.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;

public record ChangePlanRequest(
        @NotNull SubscriptionPlan plan,
        // Bắt buộc khi plan là gói trả phí (validate ở service — dùng để tính expiresAt);
        // plan = FREE thì bỏ trống
        BillingCycle billingCycle
) {
}
