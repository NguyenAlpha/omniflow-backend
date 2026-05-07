package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.auth.LoginRequest;
import com.omniflow.backend.dto.request.auth.RegisterRequest;
import com.omniflow.backend.dto.response.auth.AuthResponse;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
}
