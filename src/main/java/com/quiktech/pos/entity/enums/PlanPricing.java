package com.quiktech.pos.entity.enums;

import java.math.BigDecimal;

public enum PlanPricing {
    FREE(BigDecimal.ZERO, BigDecimal.ZERO),
    BASIC(new BigDecimal("199000"), new BigDecimal("1990000")),
    PRO(new BigDecimal("499000"), new BigDecimal("4990000"));

    public final BigDecimal monthlyPrice;
    public final BigDecimal yearlyPrice;

    PlanPricing(BigDecimal monthlyPrice, BigDecimal yearlyPrice) {
        this.monthlyPrice = monthlyPrice;
        this.yearlyPrice = yearlyPrice;
    }

    public BigDecimal priceFor(BillingCycle cycle) {
        return cycle == BillingCycle.YEARLY ? yearlyPrice : monthlyPrice;
    }
}
