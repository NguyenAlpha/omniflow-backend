package com.quiktech.backend.dto.response.common;

public record ErrorDetail(
    String code,
    String message,
    String field
) {
    public static ErrorDetail of(ErrorCode code, String message) {
        return new ErrorDetail(code.name(), message, null);
    }

    public static ErrorDetail of(ErrorCode code, String message, String field) {
        return new ErrorDetail(code.name(), message, field);
    }
}
