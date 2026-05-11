package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.user.SetUserStatusRequest;
import com.omniflow.backend.dto.request.user.UpdateProfileRequest;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.dto.response.common.PagedResult;
import com.omniflow.backend.dto.response.user.UserAdminResponse;
import com.omniflow.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResult<PagedResult<UserAdminResponse>>> listUsers(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResult.ok(userService.listUsers(q, page, size)));
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<ApiResult<UserAdminResponse>> updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResult.ok(userService.updateUser(userId, request)));
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<ApiResult<UserAdminResponse>> setUserStatus(
            @PathVariable Long userId,
            @Valid @RequestBody SetUserStatusRequest request) {
        return ResponseEntity.ok(ApiResult.ok(userService.setUserStatus(userId, request)));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResult<Void>> deleteUser(@PathVariable Long userId) {
        userService.deleteUser(userId);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
