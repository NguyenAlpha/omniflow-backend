package com.quiktech.backend.dto.response.business;

import com.quiktech.backend.dto.response.store.StoreResponse;
import com.quiktech.backend.dto.response.warehouse.WarehouseResponse;

public record BusinessDefaultResponse(
        BusinessResponse business,
        StoreResponse store,
        WarehouseResponse warehouse
) {
}
