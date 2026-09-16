package com.quiktech.pos.dto.response.catalog;

import java.util.List;

public record ProductDetailResponse(
        ProductResponse productResponse,
        List<PriceHistoryResponse> priceHistory
) {
}
