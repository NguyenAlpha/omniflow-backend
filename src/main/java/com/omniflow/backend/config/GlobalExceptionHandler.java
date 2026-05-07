package com.omniflow.backend.config;

import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.dto.response.common.ErrorDetail;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResult<?>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password"))
        );
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResult<?>> handleDisabled(DisabledException ex) {
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<?>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResult.fail(ErrorDetail.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred"))
        );
    }
}
