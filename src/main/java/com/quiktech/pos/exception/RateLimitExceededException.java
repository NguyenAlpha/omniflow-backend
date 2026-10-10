package com.quiktech.pos.exception;

import lombok.Getter;

/**
 * Hết quota được kiểm tra trong tầng service (không phải filter) — hiện là giới hạn đăng nhập
 * sai theo tài khoản. {@code GlobalExceptionHandler} trả 429 với cùng body/header như
 * {@code RateLimitResponseWriter}.
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;
    private final long limit;
    private final long remaining;

    public RateLimitExceededException(String message, long retryAfterSeconds, long limit, long remaining) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.limit = limit;
        this.remaining = remaining;
    }
}
