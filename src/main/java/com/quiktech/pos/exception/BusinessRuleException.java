package com.quiktech.pos.exception;

import com.quiktech.pos.dto.response.common.ErrorCode;
import lombok.Getter;

/**
 * Vi phạm business rule cần mã lỗi riêng (không phải VALIDATION_ERROR chung) để client
 * dịch được thông báo — VD trùng email, sai mật khẩu hiện tại. Trả về 400 như
 * {@link IllegalArgumentException}, chỉ khác {@code error.code}.
 */
@Getter
public class BusinessRuleException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
