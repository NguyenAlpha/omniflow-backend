package com.quiktech.pos.dto.response.admin;

import java.time.Instant;

public record PaymentAccountResponse(Long id, String label, String bankName, String accountNumber,
        String accountHolder, String branch, boolean active, boolean archived, Long version,
        Instant createdAt, Instant updatedAt) {
}
