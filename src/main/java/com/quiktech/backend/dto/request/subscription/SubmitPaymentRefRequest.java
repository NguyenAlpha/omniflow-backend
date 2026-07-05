package com.quiktech.backend.dto.request.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitPaymentRefRequest(
        @NotBlank @Size(max = 100) String bankTransferRef
) {
}
