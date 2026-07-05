package com.quiktech.backend.dto.response.auth;

import com.quiktech.backend.entity.enums.RoleName;

public record StoreInfo(
    Long storeId,
    String storeName,
    RoleName role,
    String positionTitle
) {
}
