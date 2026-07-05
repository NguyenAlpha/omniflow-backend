package com.quiktech.backend.dto.response.notification;

import java.math.BigDecimal;

public record LowStockItemResponse(
    String productName,
    String sku,
    BigDecimal quantity,
    Integer minStockLevel,
    String warehouseName
) {
}
