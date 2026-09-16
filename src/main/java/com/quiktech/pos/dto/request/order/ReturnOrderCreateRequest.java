package com.quiktech.pos.dto.request.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReturnOrderCreateRequest(
    @NotBlank String returnCode,
    @NotNull UUID originalOrderPublicId,
    String reason,
    @NotBlank String refundMethod,
    String note,
    @NotEmpty List<@Valid ReturnOrderItemRequest> items
) {
}
