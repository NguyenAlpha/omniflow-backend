package com.quiktech.pos.controller;

import com.quiktech.pos.dto.response.auth.UserSummaryResponse;
import com.quiktech.pos.dto.response.admin.AdminBusinessDetailResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.security.UserPrincipal;
import com.quiktech.pos.service.UserService;
import com.quiktech.pos.service.BusinessService;
import com.quiktech.pos.service.StoreService;
import com.quiktech.pos.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminOperationsController {
    private final UserService userService;
    private final BusinessService businessService;
    private final StoreService storeService;
    private final SubscriptionService subscriptionService;

    @GetMapping("/session")
    public ApiResult<UserSummaryResponse> session(@AuthenticationPrincipal UserPrincipal currentUser) {
        return ApiResult.ok(userService.getProfile(currentUser));
    }

    @GetMapping("/businesses/{businessId}")
    public ApiResult<AdminBusinessDetailResponse> business(@PathVariable Long businessId) {
        var business = businessService.getBusiness(businessId);
        return ApiResult.ok(new AdminBusinessDetailResponse(business,
                subscriptionService.get(businessId), storeService.getBusinessStores(businessId)));
    }
}
