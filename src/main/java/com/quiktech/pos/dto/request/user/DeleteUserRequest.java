package com.quiktech.pos.dto.request.user;

import jakarta.validation.constraints.Size;

public record DeleteUserRequest(@Size(max = 500) String reason) {
}
