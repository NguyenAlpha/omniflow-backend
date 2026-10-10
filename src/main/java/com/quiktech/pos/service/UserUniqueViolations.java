package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.exception.BusinessRuleException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Đổi lỗi vi phạm unique index của bảng {@code users} (DB chặn khi 2 request cùng lọt bước kiểm
 * tra trùng, hoặc register — vốn không kiểm tra trước) thành mã lỗi theo đúng field để client dịch
 * được: message của PostgreSQL chứa tên index ({@code uq_users_username_active} /
 * {@code uq_users_email_active}, xem V1__identity_access.sql).
 */
final class UserUniqueViolations {

    private UserUniqueViolations() {
    }

    static RuntimeException toException(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("uq_users_username_active")) {
            return new BusinessRuleException(ErrorCode.USERNAME_TAKEN, "Username already taken");
        }
        if (message.contains("uq_users_email_active")) {
            return new BusinessRuleException(ErrorCode.EMAIL_TAKEN, "Email already registered");
        }
        // Constraint khác (không lường trước) — giữ hành vi cũ: 400 VALIDATION_ERROR
        return new IllegalArgumentException("Username or email already taken");
    }
}
