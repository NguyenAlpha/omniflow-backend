package com.quiktech.backend.dto.request.order;

import java.util.List;
import java.util.UUID;

public record ReturnOrderCreateRequest(
    String returnCode,
    UUID originalOrderPublicId,
    UUID warehousePublicId,
    String reason,
    String refundMethod,
    String note,
    List<ReturnOrderItemRequest> items
) {
}
