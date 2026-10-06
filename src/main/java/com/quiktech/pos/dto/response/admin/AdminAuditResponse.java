package com.quiktech.pos.dto.response.admin;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record AdminAuditResponse(Long id, Long actorId, String actorName, Long businessId,
        String action, String entityType, Long entityId, String reason,
        JsonNode before, JsonNode after, Instant createdAt) {
}
