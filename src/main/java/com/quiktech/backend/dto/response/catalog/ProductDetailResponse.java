package com.quiktech.backend.dto.response.catalog;

import java.util.List;

public record ProductDetailResponse(
        ProductResponse productResponse,
        List<PriceHistoryResponse> priceHistory
) {
}
