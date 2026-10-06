package com.quiktech.pos.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "subscription_payment_settings")
@Getter @Setter @NoArgsConstructor
public class SubscriptionPaymentSettings {
    @Id
    private Integer id;
    private Long activeAccountId;
}
