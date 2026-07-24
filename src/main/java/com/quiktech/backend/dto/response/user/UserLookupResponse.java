package com.quiktech.backend.dto.response.user;

/**
 * Kết quả tra cứu user theo username — dùng khi owner cần userId để thêm thành viên
 * (business member / store member). Chỉ trả thông tin định danh tối thiểu.
 */
public record UserLookupResponse(
    Long userId,
    String username,
    String fullName,
    Boolean isActive
) {
}
