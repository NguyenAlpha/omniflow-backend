package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "subscription_invoices", indexes = {
    @Index(name = "idx_subscription_invoices_store_id", columnList = "store_id"),
    @Index(name = "idx_subscription_invoices_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 20)
    private String plan; // FREE, BASIC, PRO

    @Column(nullable = false, length = 20)
    private String billingCycle; // MONTHLY, YEARLY

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status; // PAID, PENDING, FAILED

    @Column(length = 20)
    private String paymentMethod; // BANK_TRANSFER, CARD, MOMO, ...

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant periodStart;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant periodEnd;

    @Column(columnDefinition = "TIMESTAMPTZ")
    private Instant paidAt;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt = Instant.now();

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt = Instant.now();
}

