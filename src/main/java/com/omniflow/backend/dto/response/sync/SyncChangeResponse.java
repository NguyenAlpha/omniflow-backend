package com.omniflow.backend.dto.response.sync;

import java.time.LocalDateTime;
import java.util.UUID;

public record SyncChangeResponse(
    String tableName,
    UUID recordPublicId,
    String operation,
    Long syncVersion,
    LocalDateTime changedAt,
    UUID changedByDevice
) {
}

