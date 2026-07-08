package com.quiktech.backend.entity;

import com.quiktech.backend.entity.enums.RefundMethod;
import com.quiktech.backend.entity.enums.ReturnOrderStatus;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "return_orders", indexes = {
    @Index(name = "idx_return_orders_store_id", columnList = "store_id"),
    @Index(name = "idx_return_orders_original_order_id", columnList = "original_order_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 20)
    private String returnCode;

    @ManyToOne(optional = false)
    @JoinColumn(name = "original_order_id", nullable = false)
    private Order originalOrder;

    @ManyToOne(optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnOrderStatus status; // PENDING, COMPLETED, CANCELLED

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalRefund;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundMethod refundMethod; // CASH, BANK_TRANSFER, STORE_CREDIT

    @Column(columnDefinition = "TEXT")
    private String note;

    // === Local-first sync fields ===
    @Column(nullable = false, unique = true, columnDefinition = "UUID")
    private UUID publicId;

    @Version
    @Builder.Default
    @Column(nullable = false)
    private Long syncVersion = 0L;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant lastModifiedAt = Instant.now();

    @ManyToOne
    @JoinColumn(name = "last_modified_by_user")
    private User lastModifiedByUser;

    @Column(columnDefinition = "UUID")
    private UUID lastModifiedByDevice;

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    // === Standard audit fields ===
    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt = Instant.now();

    // === Relationships ===
    @OneToMany(mappedBy = "returnOrder", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<ReturnOrderItem> returnOrderItems;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
