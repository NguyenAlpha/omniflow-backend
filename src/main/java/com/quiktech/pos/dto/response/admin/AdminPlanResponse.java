package com.quiktech.pos.dto.response.admin;

import com.quiktech.pos.entity.enums.SubscriptionPlan;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Gói cho trang admin: thêm {@code version} (gửi lại khi sửa) và {@code affectedBusinesses}
 * — số business có limit bị cập nhật nếu sửa giới hạn của gói này.
 */
public record AdminPlanResponse(
        SubscriptionPlan code,
        BigDecimal monthlyPrice,
        BigDecimal yearlyPrice,
        Integer maxStores,
        Integer maxStaff,
        Integer maxProducts,
        Integer maxWarehouses,
        Long version,
        Instant updatedAt,
        long affectedBusinesses
) {
}
