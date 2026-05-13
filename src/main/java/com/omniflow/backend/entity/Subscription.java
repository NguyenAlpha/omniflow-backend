package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "subscriptions", indexes = {
    @Index(name = "idx_subscriptions_store_id", columnList = "store_id")
})
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
    @JoinColumn(name = "store_id", nullable = false, unique = true)
    private Store store;

    @Column(nullable = false, length = 20)
    private String plan; // FREE, BASIC, PRO

    @Column(nullable = false, length = 20)
    private String status; // ACTIVE, EXPIRED, CANCELLED

    @Column(length = 20)
    private String billingCycle; // MONTHLY, YEARLY

    @Column
    private Integer maxStaff;

    @Column
    private Integer maxProducts;

    @Column
    private Integer maxWarehouses;

    @Column
    private Integer maxOrdersPerMonth;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant startedAt;

    @Column(columnDefinition = "TIMESTAMPTZ")
    private Instant expiresAt;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt = Instant.now();

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt = Instant.now();
}

