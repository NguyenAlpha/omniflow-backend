package com.quiktech.backend.entity;

import com.quiktech.backend.entity.enums.PurchaseOrderStatus;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "purchase_orders", indexes = {
    @Index(name = "idx_po_store_status", columnList = "store_id, status"),
    @Index(name = "idx_purchase_orders_supplier_id", columnList = "supplier_id"),
    @Index(name = "idx_purchase_orders_warehouse_id", columnList = "warehouse_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 20)
    private String orderCode;

    @ManyToOne(optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Builder.Default
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal debtAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String paymentMethod = "CASH";

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
    @BatchSize(size = 20)
    @OneToMany(mappedBy = "purchaseOrder", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<PurchaseOrderItem> purchaseOrderItems;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
