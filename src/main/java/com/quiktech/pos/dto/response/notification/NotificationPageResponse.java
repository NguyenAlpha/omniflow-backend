package com.quiktech.pos.dto.response.notification;

import java.util.List;

public record NotificationPageResponse(List<NotificationResponse> content, Long nextCursor) {
}
