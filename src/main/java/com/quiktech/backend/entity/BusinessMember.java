package com.quiktech.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_members", indexes = {
    @Index(name = "idx_business_members_user_id", columnList = "user_id"),
    @Index(name = "idx_business_members_business_id", columnList = "business_id")
})
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column
    private LocalDate joinedDate;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    // === Local-first sync fields ===
    @Column(nullable = false, unique = true, columnDefinition = "UUID")
    private UUID publicId;

    @Builder.Default
    @Column(nullable = false)
    private Long syncVersion = 0L;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant lastModifiedAt = Instant.now();

    @ManyToOne
    @JoinColumn(name = "last_modified_by_user")
    private User lastModifiedByUser;

    // === Standard audit fields ===
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
