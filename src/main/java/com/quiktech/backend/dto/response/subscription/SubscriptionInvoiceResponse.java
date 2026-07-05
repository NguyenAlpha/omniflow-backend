package com.quiktech.backend.dto.response.subscription;

import com.quiktech.backend.entity.enums.BillingCycle;
import com.quiktech.backend.entity.enums.InvoiceStatus;
import com.quiktech.backend.entity.enums.SubscriptionPlan;

import java.math.BigDecimal;
import java.time.Instant;

public record SubscriptionInvoiceResponse(
        Long id,
        Long businessId,
        SubscriptionPlan plan,
        BillingCycle billingCycle,
        BigDecimal amount,
        InvoiceStatus status,
        String bankTransferRef,
        String adminNote,
        Instant periodStart,
        Instant periodEnd,
        Instant paidAt,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
