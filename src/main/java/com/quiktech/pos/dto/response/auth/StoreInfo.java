package com.quiktech.pos.dto.response.auth;

import com.quiktech.pos.entity.enums.RoleName;

public record StoreInfo(
    Long storeId,
    String storeName,
    RoleName role,
    String positionTitle
) {
}
