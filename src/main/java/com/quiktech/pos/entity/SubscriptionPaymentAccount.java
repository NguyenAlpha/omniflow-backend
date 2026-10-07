package com.quiktech.pos.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "subscription_payment_accounts")
@Getter @Setter @NoArgsConstructor
public class SubscriptionPaymentAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String label;
    @Column(nullable = false, length = 150)
    private String bankName;
    @Column(nullable = false, length = 50)
    private String accountNumber;
    @Column(nullable = false, length = 150)
    private String accountHolder;
    @Column(nullable = false, length = 150)
    private String branch = "";
    @Column(length = 40)
    private String qrImageKey;
    @Column(nullable = false)
    private boolean archived;
    @Version
    private Long version;
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
