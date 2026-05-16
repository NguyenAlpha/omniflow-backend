package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.user.ChangePasswordRequest;
import com.quiktech.backend.dto.request.user.UpdateProfileRequest;
import com.quiktech.backend.dto.response.auth.UserSummaryResponse;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResult<UserSummaryResponse>> getProfile(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(userService.getProfile(currentUser)));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResult<UserSummaryResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(userService.updateProfile(currentUser, request)));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<ApiResult<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        userService.changePassword(currentUser, request);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
