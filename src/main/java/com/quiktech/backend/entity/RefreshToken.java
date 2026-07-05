package com.quiktech.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant expiresAt;

    @Column(columnDefinition = "TIMESTAMPTZ")
    private Instant revokedAt;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt = Instant.now();
}
