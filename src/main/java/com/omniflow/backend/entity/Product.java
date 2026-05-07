package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_products_store_id", columnList = "store_id"),
    @Index(name = "idx_products_category_id", columnList = "category_id")
})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;
    
    @Column(nullable = false, length = 50)
    private String sku;
    
    @Column(nullable = false, length = 200)
    private String name;
    
    @Column(columnDefinition = "TEXT")
    private String description;
    
    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;
    
    @ManyToOne(optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private Unit unit;
    
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal costPrice;
    
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal sellingPrice;
    
    @Column(nullable = false)
    private Integer minStockLevel = 0;
    
    @Column(nullable = false)
    private Boolean isActive = true;
    
    @Column(columnDefinition = "TSVECTOR")
    private String searchVector; // for full-text search
    
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
    
    // === Standard audit fields ===
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    @Column(columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime deletedAt;
}

