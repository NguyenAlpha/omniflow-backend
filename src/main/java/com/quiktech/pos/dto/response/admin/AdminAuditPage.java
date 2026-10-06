package com.quiktech.pos.dto.response.admin;

import java.util.List;

public record AdminAuditPage(List<AdminAuditResponse> content, Long nextCursor) {
}
