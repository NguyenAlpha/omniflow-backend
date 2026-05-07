package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

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
    private LocalDateTime startedAt;

    @Column(columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime expiresAt;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime updatedAt = LocalDateTime.now();
}

