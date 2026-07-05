package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.auth.LoginRequest;
import com.quiktech.backend.dto.request.auth.RefreshTokenRequest;
import com.quiktech.backend.dto.request.auth.RegisterRequest;
import com.quiktech.backend.dto.response.auth.AuthResponse;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResult<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(ApiResult.ok(authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResult<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResult.ok(authService.login(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResult<AuthResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResult.ok(authService.refresh(request.refreshToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResult<Void>> logout(@AuthenticationPrincipal UserPrincipal userPrincipal) {
        authService.logout(userPrincipal.userId());
        return ResponseEntity.ok(ApiResult.ok(null));
    }
}
