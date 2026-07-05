package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.subscription.ChangePlanRequest;
import com.quiktech.backend.dto.request.subscription.ConfirmInvoiceRequest;
import com.quiktech.backend.dto.request.subscription.RejectInvoiceRequest;
import com.quiktech.backend.dto.response.admin.AdminStatsResponse;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.subscription.SubscriptionInvoiceResponse;
import com.quiktech.backend.dto.response.subscription.SubscriptionResponse;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.AdminStatsService;
import com.quiktech.backend.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/subscriptions")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final AdminStatsService adminStatsService;

    @GetMapping("/stats")
    public ResponseEntity<ApiResult<AdminStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResult.ok(adminStatsService.getStats()));
    }

    @GetMapping("/{businessId}")
    public ResponseEntity<ApiResult<SubscriptionResponse>> get(@PathVariable Long businessId) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.get(businessId)));
    }

    @PatchMapping("/{businessId}/plan")
    public ResponseEntity<ApiResult<SubscriptionResponse>> changePlan(
            @PathVariable Long businessId,
            @Valid @RequestBody ChangePlanRequest request) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.changePlan(businessId, request.plan())));
    }

    @GetMapping("/invoices/pending")
    public ResponseEntity<ApiResult<Page<SubscriptionInvoiceResponse>>> listPendingInvoices(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.listPendingInvoices(pageable)));
    }

    @PostMapping("/invoices/{invoiceId}/confirm")
    public ResponseEntity<ApiResult<SubscriptionInvoiceResponse>> confirmInvoice(
            @PathVariable Long invoiceId,
            @Valid @RequestBody ConfirmInvoiceRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(
                subscriptionService.confirmInvoice(invoiceId, currentUser.userId(), request.adminNote())));
    }

    @PostMapping("/invoices/{invoiceId}/reject")
    public ResponseEntity<ApiResult<SubscriptionInvoiceResponse>> rejectInvoice(
            @PathVariable Long invoiceId,
            @Valid @RequestBody RejectInvoiceRequest request) {
        return ResponseEntity.ok(ApiResult.ok(
                subscriptionService.rejectInvoice(invoiceId, request.adminNote())));
    }
}
