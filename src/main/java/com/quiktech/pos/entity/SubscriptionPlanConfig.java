package com.quiktech.pos.entity;

import com.quiktech.pos.entity.enums.BillingCycle;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Giá và giới hạn của một gói (bảng subscription_plans) — admin sửa được, thay cho
 * enum PlanPricing/PlanLimits cũ. {@code max*} null = không giới hạn.
 */
@Entity
@Table(name = "subscription_plans")
@Getter @Setter @NoArgsConstructor
public class SubscriptionPlanConfig {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SubscriptionPlan code;
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyPrice;
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal yearlyPrice;
    private Integer maxStores;
    private Integer maxStaff;
    private Integer maxProducts;
    private Integer maxWarehouses;
    @Version
    private Long version;
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public BigDecimal priceFor(BillingCycle cycle) {
        return cycle == BillingCycle.YEARLY ? yearlyPrice : monthlyPrice;
    }

    /** Chép 4 giới hạn của gói sang subscription (subscriptions.max_* là bản sao). */
    public void applyLimitsTo(Subscription sub) {
        sub.setMaxStores(maxStores);
        sub.setMaxStaff(maxStaff);
        sub.setMaxProducts(maxProducts);
        sub.setMaxWarehouses(maxWarehouses);
    }
}
