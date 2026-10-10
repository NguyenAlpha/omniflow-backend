package com.quiktech.pos.dto.response.subscription;

import com.quiktech.pos.entity.enums.SubscriptionPlan;

import java.math.BigDecimal;

/** Giá và giới hạn công khai của một gói. {@code max*} null = không giới hạn. */
public record PlanResponse(
        SubscriptionPlan code,
        BigDecimal monthlyPrice,
        BigDecimal yearlyPrice,
        Integer maxStores,
        Integer maxStaff,
        Integer maxProducts,
        Integer maxWarehouses
) {
}
