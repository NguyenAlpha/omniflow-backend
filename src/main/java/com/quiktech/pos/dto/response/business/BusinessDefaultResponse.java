package com.quiktech.pos.dto.response.business;

import com.quiktech.pos.dto.response.store.StoreResponse;
import com.quiktech.pos.dto.response.warehouse.WarehouseResponse;

public record BusinessDefaultResponse(
        BusinessResponse business,
        StoreResponse store,
        WarehouseResponse warehouse
) {
}
