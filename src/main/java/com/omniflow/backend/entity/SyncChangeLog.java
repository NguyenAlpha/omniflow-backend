package com.omniflow.backend.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sync_change_log", indexes = {
    @Index(name = "idx_sync_log_store_id", columnList = "store_id"),
    @Index(name = "idx_sync_log_version", columnList = "sync_version")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 50)
    private String tableName;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID recordPublicId;

    @Column(nullable = false, length = 10)
    private String operation; // INSERT, UPDATE, DELETE

    @Column(nullable = false)
    private Long syncVersion;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private LocalDateTime changedAt = LocalDateTime.now();

    @Column(columnDefinition = "UUID")
    private UUID changedByDevice;
}

