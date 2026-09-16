package com.quiktech.pos.dto.response.notification;

public record NotificationSummaryResponse(
    long lowStockCount,
    long pendingInvoiceCount
) {
}
