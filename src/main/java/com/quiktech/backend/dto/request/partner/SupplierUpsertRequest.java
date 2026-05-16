package com.quiktech.backend.dto.request.partner;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SupplierUpsertRequest(
    @NotBlank @Size(max = 20) String code,
    @NotBlank @Size(max = 200) String name,
    @Pattern(regexp = "^$|^[0-9+\\-() ]{8,20}$") String phone,
    @Email @Size(max = 100) String email,
    @Size(max = 2000) String address
) {
}

