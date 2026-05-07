package com.omniflow.backend.exception;

import com.omniflow.backend.dto.response.common.ErrorCode;
import lombok.Getter;

@Getter
public class ForbiddenException extends RuntimeException {

    private final ErrorCode errorCode;

    public ForbiddenException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
