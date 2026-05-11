package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_roles", indexes = {
    @Index(name = "idx_user_roles_user_id", columnList = "user_id"),
    @Index(name = "idx_user_roles_role_id", columnList = "role_id"),
    @Index(name = "idx_user_roles_store_id", columnList = "store_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    // NULL = global/system role; NOT NULL = store-scoped role
    @ManyToOne
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne
    @JoinColumn(name = "granted_by")
    private User grantedBy;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime deletedAt;
}
