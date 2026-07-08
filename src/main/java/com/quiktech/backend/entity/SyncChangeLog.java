package com.quiktech.backend.entity;

import com.quiktech.backend.entity.enums.SyncOperation;
import lombok.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sync_change_log", indexes = {
    @Index(name = "idx_sync_log_store_version", columnList = "store_id, sync_version")
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SyncOperation operation; // INSERT, UPDATE, DELETE

    @Column(nullable = false)
    private Long syncVersion;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant changedAt = Instant.now();

    @Column(columnDefinition = "UUID")
    private UUID changedByDevice;
}

