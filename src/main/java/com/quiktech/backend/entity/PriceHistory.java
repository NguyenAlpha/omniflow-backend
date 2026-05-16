package com.quiktech.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "price_history", indexes = {
    @Index(name = "idx_price_history_product_id", columnList = "product_id"),
    @Index(name = "idx_price_history_store_id", columnList = "store_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal oldCostPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal newCostPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal oldSellingPrice;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal newSellingPrice;

    @ManyToOne(optional = false)
    @JoinColumn(name = "changed_by", nullable = false)
    private User changedBy;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant changedAt = Instant.now();
}

