package com.omniflow.backend.dto.request.warehouse;

public record WarehouseUpsertRequest(
    String name,
    String address,
    Boolean isActive
) {
}
