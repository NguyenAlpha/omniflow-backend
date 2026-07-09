package com.quiktech.backend.dto.request.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    // Cấm ký tự @ — username dạng email sẽ xung đột với findByUsernameOrEmail(x, x)
    // (trả 2 rows nếu username của user A trùng email của user B → login nổ 500)
    @NotBlank @Size(max = 50) @Pattern(regexp = "^[a-zA-Z0-9._-]+$") String username,
    @NotBlank @Email @Size(max = 100) String email,
    // Max 72: BCrypt chỉ xử lý 72 byte đầu — Spring Security 6.5 ném exception nếu dài hơn
    @NotBlank @Size(min = 6, max = 72) String password,
    @NotBlank @Size(max = 200) String fullName,
    @Pattern(regexp = "^$|^[0-9+\\-() ]{8,20}$") String phone
) {
}

