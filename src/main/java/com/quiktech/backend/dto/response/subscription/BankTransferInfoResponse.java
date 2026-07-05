package com.quiktech.backend.dto.response.subscription;

public record BankTransferInfoResponse(
        String bankName,
        String accountNumber,
        String accountHolder,
        String branch
) {
}
