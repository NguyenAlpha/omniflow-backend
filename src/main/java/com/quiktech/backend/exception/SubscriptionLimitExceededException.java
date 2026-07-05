package com.quiktech.backend.exception;

import com.quiktech.backend.dto.response.common.ErrorCode;
import lombok.Getter;

@Getter
public class SubscriptionLimitExceededException extends RuntimeException {

    private final ErrorCode errorCode = ErrorCode.SUBSCRIPTION_LIMIT_EXCEEDED;

    public SubscriptionLimitExceededException(String message) {
        super(message);
    }
}
