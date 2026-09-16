package com.quiktech.pos.dto.request.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectInvoiceRequest(
        @NotBlank @Size(max = 500) String adminNote
) {
}
