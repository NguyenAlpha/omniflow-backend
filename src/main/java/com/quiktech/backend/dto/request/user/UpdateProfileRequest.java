package com.quiktech.backend.dto.request.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    // Cấm ký tự @ — username dạng email xung đột với findByUsernameOrEmail (xem RegisterRequest)
    @NotBlank @Size(max = 50) @Pattern(regexp = "^[a-zA-Z0-9._-]+$") String username,
    @NotBlank @Email @Size(max = 100) String email,
    @NotBlank @Size(max = 200) String fullName,
    @Size(max = 20) String phone
) {
}
