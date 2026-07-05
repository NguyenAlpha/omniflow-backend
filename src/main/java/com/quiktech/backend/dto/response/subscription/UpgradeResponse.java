package com.quiktech.backend.dto.response.subscription;

public record UpgradeResponse(
        SubscriptionInvoiceResponse invoice,
        BankTransferInfoResponse bankInfo
) {
}
