package com.quiktech.pos.dto.request.subscription;

import jakarta.validation.constraints.Size;

public record ConfirmInvoiceRequest(
        @Size(max = 500) String adminNote
) {
}
