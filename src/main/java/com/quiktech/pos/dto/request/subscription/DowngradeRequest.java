package com.quiktech.pos.dto.request.subscription;

import com.quiktech.pos.entity.enums.SubscriptionPlan;
import jakarta.validation.constraints.NotNull;

public record DowngradeRequest(
        @NotNull SubscriptionPlan plan
) {
}
