package com.quiktech.pos.entity;

import com.quiktech.pos.entity.enums.BillingCycle;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;
import lombok.*;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "business_id", nullable = false, unique = true)
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BillingCycle billingCycle;

    @Column
    private Integer maxStores;

    @Column
    private Integer maxStaff;

    @Column
    private Integer maxProducts;

    @Column
    private Integer maxWarehouses;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant startedAt;

    @Column(columnDefinition = "TIMESTAMPTZ")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_plan", length = 20)
    private SubscriptionPlan pendingPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "pending_billing_cycle", length = 20)
    private BillingCycle pendingBillingCycle;

    // Thời điểm đã gửi email cảnh báo sắp hết hạn cho chu kỳ hiện tại — chống spam
    // (scheduler chạy hàng ngày, không có cờ này sẽ gửi lặp 7 email trong 7 ngày cuối).
    // Reset về null khi kích hoạt chu kỳ mới (confirmInvoice / changePlan).
    @Column(name = "expiry_warning_sent_at", columnDefinition = "TIMESTAMPTZ")
    private Instant expiryWarningSentAt;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
