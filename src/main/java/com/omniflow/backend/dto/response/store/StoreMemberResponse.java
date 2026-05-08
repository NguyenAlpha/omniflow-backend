package com.omniflow.backend.dto.response.store;

import com.omniflow.backend.entity.enums.RoleName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record StoreMemberResponse(
    Long id,
    UUID publicId,
    Long userId,
    String username,
    Long storeId,
    RoleName role,
    String positionTitle,
    LocalDate joinedDate,
    Boolean isActive,
    Long syncVersion,
    LocalDateTime lastModifiedAt
) {
}
