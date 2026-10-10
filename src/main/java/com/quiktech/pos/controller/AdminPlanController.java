package com.quiktech.pos.controller;

import com.quiktech.pos.dto.request.subscription.PlanUpdateRequest;
import com.quiktech.pos.dto.response.admin.AdminPlanResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.service.PlanCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/plans")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminPlanController {

    private final PlanCatalogService planCatalogService;

    @GetMapping
    public ApiResult<List<AdminPlanResponse>> list() {
        return ApiResult.ok(planCatalogService.listForAdmin());
    }

    @PutMapping("/{code}")
    public ApiResult<AdminPlanResponse> update(@PathVariable SubscriptionPlan code, @Valid @RequestBody PlanUpdateRequest request) {
        return ApiResult.ok(planCatalogService.update(code, request));
    }
}
