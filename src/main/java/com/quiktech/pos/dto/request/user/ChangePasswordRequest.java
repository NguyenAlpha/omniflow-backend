package com.quiktech.pos.dto.request.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank String currentPassword,
    // Max 72: BCrypt chỉ xử lý 72 byte đầu — Spring Security 6.5 ném exception nếu dài hơn
    @NotBlank @Size(min = 6, max = 72) String newPassword
) {
}
