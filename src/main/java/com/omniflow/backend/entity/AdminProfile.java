package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import com.omniflow.backend.entity.enums.SystemRole;

@Entity
@Table(name = "admin_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SystemRole systemRole; // SUPER_ADMIN, SUPPORT

    @Column(length = 100)
    private String department;

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime deletedAt;
}
