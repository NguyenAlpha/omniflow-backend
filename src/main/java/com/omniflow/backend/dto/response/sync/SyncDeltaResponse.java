package com.omniflow.backend.dto.response.sync;

import java.util.List;

public record SyncDeltaResponse(
    Long latestSyncVersion,
    List<SyncChangeResponse> changes
) {
}

