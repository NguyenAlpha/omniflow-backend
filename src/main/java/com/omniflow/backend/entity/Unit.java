package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "units", indexes = {
    @Index(name = "idx_units_store_id", columnList = "store_id")
})
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "store_id")
    private Store store; // null = system unit

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 10)
    private String abbreviation;

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
    @Column(columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime deletedAt;
}
