package com.quiktech.pos.controller;

import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.dto.response.subscription.PlanResponse;
import com.quiktech.pos.service.PlanCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Công khai (permitAll trong SecurityConfig): trang landing cho khách chưa đăng nhập hiển thị bảng gói
@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanCatalogService planCatalogService;

    @GetMapping
    public ApiResult<List<PlanResponse>> list() {
        return ApiResult.ok(planCatalogService.list());
    }
}
