package com.quiktech.pos.dto.request.business;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BusinessCreateRequest(
        @NotBlank @Size(max = 30) String name,
        String address,
        @Pattern(regexp = "^$|^[0-9+\\-() ]{8,12}$") String phone,
        @Email @Size(max = 30) String email
) {
}
