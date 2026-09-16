package com.quiktech.pos.exception;

import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.common.ErrorDetail;
import com.quiktech.pos.exception.InvalidTokenException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<?>> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst();
        ErrorDetail error = fieldError.map(fe ->
                ErrorDetail.of(ErrorCode.VALIDATION_ERROR, fe.getDefaultMessage(), fe.getField())
        ).orElse(
                ErrorDetail.of(ErrorCode.VALIDATION_ERROR, "Validation failed")
        );
        return ResponseEntity.badRequest().body(ApiResult.fail(error));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<?>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.VALIDATION_ERROR, ex.getMessage()))
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResult<?>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.badRequest().body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.INSUFFICIENT_STOCK, ex.getMessage()))
        );
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiResult<?>> handleInvalidToken(InvalidTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResult.fail(ErrorDetail.of(ex.getErrorCode(), ex.getMessage()))
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResult<?>> handleBadCredentials(BadCredentialsException ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password"))
        );
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResult<?>> handleDisabled(DisabledException ignored) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.INVALID_CREDENTIALS, "Account is disabled"))
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResult<?>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResult.fail(ErrorDetail.of(ex.getErrorCode(), ex.getMessage()))
        );
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResult<?>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResult.fail(ErrorDetail.of(ex.getErrorCode(), ex.getMessage()))
        );
    }

    @ExceptionHandler(SubscriptionLimitExceededException.class)
    public ResponseEntity<ApiResult<?>> handleSubscriptionLimit(SubscriptionLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(
                ApiResult.fail(ErrorDetail.of(ex.getErrorCode(), ex.getMessage()))
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResult<?>> handleAccessDenied(AccessDeniedException ignored) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.FORBIDDEN, "Access denied"))
        );
    }

    // @Version trên entity (Customer/Supplier.syncVersion...) ném exception này khi hai
    // request cùng sửa một bản ghi — trả 409 để client thử lại thay vì 500
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResult<?>> handleOptimisticLock(OptimisticLockingFailureException ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.CONCURRENT_MODIFICATION,
                        "The record was modified by another request. Please retry"))
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<?>> handleGeneric(Exception ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred"))
        );
    }
}
