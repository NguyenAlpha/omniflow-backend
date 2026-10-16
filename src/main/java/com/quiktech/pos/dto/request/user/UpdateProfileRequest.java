package com.quiktech.pos.dto.request.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    // Cấm ký tự @ — username dạng email xung đột với findByUsernameOrEmail (xem RegisterRequest)
    @NotBlank @Size(max = 30) @Pattern(regexp = "^[a-zA-Z0-9._-]+$") String username,
    @NotBlank @Email @Size(max = 30) String email,
    @NotBlank @Size(max = 30) String fullName,
    @Size(max = 12) String phone
) {
}
