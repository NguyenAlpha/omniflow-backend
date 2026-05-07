package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @Column(nullable = false, length = 20)
    private String status; // PENDING, COMPLETED, CANCELLED

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalRefund;

    @Column(nullable = false, length = 20)
    private String refundMethod; // CASH, BANK_TRANSFER, STORE_CREDIT

    @Column(columnDefinition = "TEXT")
    private String note;

    // === Local-first sync fields ===
    @Column(nullable = false, unique = true, columnDefinition = "UUID")
    private UUID publicId;

    @Column(nullable = false)
    private Long syncVersion = 0L;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime lastModifiedAt = LocalDateTime.now();

    @ManyToOne
    @JoinColumn(name = "last_modified_by_user")
    private User lastModifiedByUser;

    @Column(columnDefinition = "UUID")
    private UUID lastModifiedByDevice;

    @ManyToOne(optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    // === Standard audit fields ===
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime updatedAt = LocalDateTime.now();

    // === Relationships ===
    @OneToMany(mappedBy = "returnOrder", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReturnOrderItem> returnOrderItems;
}

