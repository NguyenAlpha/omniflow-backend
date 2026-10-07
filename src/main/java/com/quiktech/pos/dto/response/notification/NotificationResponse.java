package com.quiktech.pos.dto.response.notification;

import java.time.Instant;

public record NotificationResponse(long id, String type, String subject, String detail,
        String targetPath, Instant createdAt, boolean read, boolean resolved) {
}
