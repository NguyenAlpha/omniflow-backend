package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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
    private LocalDateTime periodStart;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime periodEnd;

    @Column(columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime paidAt;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime updatedAt = LocalDateTime.now();
}

