package com.quiktech.backend.controller;

import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.dashboard.DashboardResponse;
import com.quiktech.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stores/{storeId}/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<DashboardResponse>> get(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResult.ok(dashboardService.getDashboard(storeId)));
    }
}
