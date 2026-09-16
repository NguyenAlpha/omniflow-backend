package com.quiktech.pos.dto.response.subscription;

public record UpgradeResponse(
        SubscriptionInvoiceResponse invoice,
        BankTransferInfoResponse bankInfo
) {
}
