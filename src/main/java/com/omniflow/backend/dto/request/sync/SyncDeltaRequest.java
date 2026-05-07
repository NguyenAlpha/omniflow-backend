package com.omniflow.backend.dto.request.sync;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SyncDeltaRequest(
    @NotNull Long lastSyncVersion,
    String tableName,
    UUID deviceId
) {
}

