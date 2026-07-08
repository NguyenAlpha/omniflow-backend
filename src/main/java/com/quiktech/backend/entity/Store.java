package com.quiktech.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.Instant;

@Entity
@Table(name = "stores", indexes = {
    @Index(name = "idx_stores_business_id", columnList = "business_id"),
    @Index(name = "idx_stores_name", columnList = "name"),
    @Index(name = "idx_stores_deleted_at", columnList = "deleted_at")
})
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt = Instant.now();

    @Column(columnDefinition = "TIMESTAMPTZ")
    private Instant deletedAt;

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
