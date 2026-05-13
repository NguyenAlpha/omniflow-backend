package com.omniflow.backend.dto.response.sync;

import java.time.Instant;
import java.util.UUID;

public record SyncChangeResponse(
    String tableName,
    UUID recordPublicId,
    String operation,
    Long syncVersion,
    Instant changedAt,
    UUID changedByDevice
) {
}

