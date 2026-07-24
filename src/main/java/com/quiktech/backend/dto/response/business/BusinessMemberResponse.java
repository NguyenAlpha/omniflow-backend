package com.quiktech.backend.dto.response.business;

import com.quiktech.backend.entity.enums.RoleName;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

public record BusinessMemberResponse(
    Long id,
    UUID publicId,
    Long userId,
    String username,
    Long businessId,
    RoleName role,
    LocalDate joinedDate,
    Boolean isActive,
    Long syncVersion,
    Instant lastModifiedAt
) {
}
