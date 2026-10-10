package com.quiktech.pos.dto.response.catalog;

/**
 * Số product hiện có so với giới hạn gói. {@code maxProducts = null} = không giới hạn.
 */
public record ProductLimitResponse(
        boolean canCreate,
        long currentProducts,
        Integer maxProducts
) {
}
