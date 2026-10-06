package com.quiktech.pos.dto.request.subscription;

import jakarta.validation.constraints.*;

public record PaymentAccountRequest(
        @NotBlank @Size(max = 100) String label,
        @NotBlank @Size(max = 150) String bankName,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9]{4,50}", message = "Account number must contain 4–50 letters or digits") String accountNumber,
        @NotBlank @Size(max = 150) String accountHolder,
        @Size(max = 150) String branch,
        @PositiveOrZero Long version,
        @Size(max = 500) String reason) {
}
