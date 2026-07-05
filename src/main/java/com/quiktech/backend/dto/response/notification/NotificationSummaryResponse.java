package com.quiktech.backend.dto.response.notification;

public record NotificationSummaryResponse(
    long lowStockCount,
    long pendingInvoiceCount
) {
}
