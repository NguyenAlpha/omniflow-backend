package com.omniflow.backend.repository;

import com.omniflow.backend.entity.SyncChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface SyncChangeLogRepository extends JpaRepository<SyncChangeLog, Long> {

    // Get changes since last sync version
    @Query("""
        SELECT scl FROM SyncChangeLog scl 
        WHERE scl.store.id = :storeId 
        AND scl.syncVersion > :lastSyncVersion
        ORDER BY scl.syncVersion ASC
    """)
    List<SyncChangeLog> getDeltaChanges(
        @Param("storeId") Long storeId,
        @Param("lastSyncVersion") Long lastSyncVersion
    );

    // Get changes for specific table
    @Query("""
        SELECT scl FROM SyncChangeLog scl 
        WHERE scl.store.id = :storeId 
        AND scl.tableName = :tableName 
        AND scl.syncVersion > :lastSyncVersion
        ORDER BY scl.syncVersion ASC
    """)
    List<SyncChangeLog> getDeltaChangesByTable(
        @Param("storeId") Long storeId,
        @Param("tableName") String tableName,
        @Param("lastSyncVersion") Long lastSyncVersion
    );

    // Get latest sync version for store
    @Query("""
        SELECT MAX(scl.syncVersion) FROM SyncChangeLog scl 
        WHERE scl.store.id = :storeId
    """)
    Long getLatestSyncVersion(@Param("storeId") Long storeId);

    // Get specific record changes
    @Query("""
        SELECT scl FROM SyncChangeLog scl 
        WHERE scl.store.id = :storeId 
        AND scl.recordPublicId = :recordPublicId
        ORDER BY scl.syncVersion DESC
    """)
    List<SyncChangeLog> getRecordChangeHistory(
        @Param("storeId") Long storeId,
        @Param("recordPublicId") UUID recordPublicId
    );

    // Count changes by operation
    long countByStoreIdAndOperation(Long storeId, String operation);

    // Get changes by device
    List<SyncChangeLog> findByStoreIdAndChangedByDeviceOrderBySyncVersionAsc(
        Long storeId,
        UUID deviceId
    );
}

