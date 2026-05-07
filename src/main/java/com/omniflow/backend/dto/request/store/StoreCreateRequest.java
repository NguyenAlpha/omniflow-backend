package com.omniflow.backend.dto.request.store;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StoreCreateRequest(
    @NotBlank @Size(max = 200) String name,
    String address,
    @Pattern(regexp = "^$|^[0-9+\\-() ]{8,20}$") String phone,
    @Email @Size(max = 100) String email
) {
}

