package com.quiktech.backend.repository;

import com.quiktech.backend.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    // Logs for specific table record
    List<AuditLog> findByTableNameAndRecordIdOrderByCreatedAtDesc(String tableName, Long recordId);

    // Logs by user
    Page<AuditLog> findByPerformedByIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // Logs by store
    Page<AuditLog> findByStoreIdOrderByCreatedAtDesc(Long storeId, Pageable pageable);

    // Logs by action
    List<AuditLog> findByStoreIdAndActionOrderByCreatedAtDesc(Long storeId, String action);

    // Logs for table
    List<AuditLog> findByStoreIdAndTableNameOrderByCreatedAtDesc(Long storeId, String tableName);

    // Logs by date range
    List<AuditLog> findByStoreIdAndCreatedAtBetweenOrderByCreatedAtDesc(
        Long storeId,
        LocalDateTime startDate,
        LocalDateTime endDate
    );
}

