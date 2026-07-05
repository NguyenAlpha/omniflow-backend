package com.quiktech.backend.exception;

import com.quiktech.backend.dto.response.common.ErrorCode;
import lombok.Getter;

@Getter
public class InvalidTokenException extends RuntimeException {

    private final ErrorCode errorCode;

    public InvalidTokenException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
