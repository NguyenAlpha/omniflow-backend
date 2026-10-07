package com.quiktech.pos.dto.response.subscription;

public record BankTransferInfoResponse(
        String bankName,
        String accountNumber,
        String accountHolder,
        String branch,
        String qrImageUrl
) {
}
