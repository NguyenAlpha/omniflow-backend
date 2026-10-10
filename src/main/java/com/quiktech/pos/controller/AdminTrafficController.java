package com.quiktech.pos.controller;

import com.quiktech.pos.dto.response.admin.SystemHealthResponse;
import com.quiktech.pos.dto.response.admin.TrafficReportResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.service.ApiTrafficService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/traffic")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminTrafficController {

    private final ApiTrafficService apiTrafficService;

    /** Thống kê lưu lượng API trong khung {@code range}: 1h, 24h, 7d hoặc 30d. */
    @GetMapping
    public ApiResult<TrafficReportResponse> report(@RequestParam(defaultValue = "24h") String range) {
        return ApiResult.ok(apiTrafficService.report(range));
    }

    /** Tình trạng tức thời của instance API (JVM, CPU, connection pool DB, health). */
    @GetMapping("/system")
    public ApiResult<SystemHealthResponse> system() {
        return ApiResult.ok(apiTrafficService.system());
    }
}
