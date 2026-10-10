package com.quiktech.pos.dto.request.subscription;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Admin sửa giá/giới hạn của một gói. {@code max*} null = không giới hạn. */
public record PlanUpdateRequest(
        @NotNull @PositiveOrZero @Digits(integer = 13, fraction = 2) BigDecimal monthlyPrice,
        @NotNull @PositiveOrZero @Digits(integer = 13, fraction = 2) BigDecimal yearlyPrice,
        @PositiveOrZero Integer maxStores,
        @PositiveOrZero Integer maxStaff,
        @PositiveOrZero Integer maxProducts,
        @PositiveOrZero Integer maxWarehouses,
        @NotNull @PositiveOrZero Long version,
        @Size(max = 500) String reason
) {
}
