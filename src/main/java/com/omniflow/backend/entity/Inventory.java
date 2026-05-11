package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "inventory", indexes = {
    @Index(name = "idx_inventory_product_id", columnList = "product_id"),
    @Index(name = "idx_inventory_warehouse_id", columnList = "warehouse_id")
})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Builder.Default
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal quantity = BigDecimal.ZERO;

    // === Local-first sync fields ===
    @Column(nullable = false, unique = true, columnDefinition = "UUID")
    private UUID publicId;

    @Builder.Default
    @Column(nullable = false)
    private Long syncVersion = 0L;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime lastModifiedAt = LocalDateTime.now();

    @ManyToOne
    @JoinColumn(name = "last_modified_by_user")
    private User lastModifiedByUser;

    @Column(columnDefinition = "UUID")
    private UUID lastModifiedByDevice;

    // === Standard audit fields ===
    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime deletedAt;
}
