package com.quiktech.backend.dto.response.business;

import java.time.Instant;

public record BusinessResponse(
        Long id,
        String name,
        String address,
        String phone,
        String email,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
}
