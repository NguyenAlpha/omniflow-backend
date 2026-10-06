package com.quiktech.pos.dto.request.subscription;

import jakarta.validation.constraints.*;

public record PaymentAccountActionRequest(@NotNull @PositiveOrZero Long version, @Size(max = 500) String reason) {
}
