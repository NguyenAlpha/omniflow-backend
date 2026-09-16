package com.quiktech.pos.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_user_id",     columnList = "user_id"),
        @Index(name = "idx_audit_logs_store_id",    columnList = "store_id"),
        @Index(name = "idx_audit_logs_business_id", columnList = "business_id"),
        @Index(name = "idx_audit_logs_entity",      columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_logs_created_at",  columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "business_id")
    private Long businessId;

    @Column(name = "store_id")
    private Long storeId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(nullable = false, length = 50, name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "old_value")
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", name = "new_value")
    private String newValue;

    @Column(length = 45)
    private String ip;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt = Instant.now();
}
