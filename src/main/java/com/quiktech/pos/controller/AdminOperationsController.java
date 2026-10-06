package com.quiktech.pos.controller;

import com.quiktech.pos.dto.response.auth.UserSummaryResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.security.UserPrincipal;
import com.quiktech.pos.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminOperationsController {
    private final UserService userService;

    @GetMapping("/session")
    public ApiResult<UserSummaryResponse> session(@AuthenticationPrincipal UserPrincipal currentUser) {
        return ApiResult.ok(userService.getProfile(currentUser));
    }
}
