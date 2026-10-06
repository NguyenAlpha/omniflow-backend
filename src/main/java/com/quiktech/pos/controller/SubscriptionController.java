package com.quiktech.pos.controller;

import com.quiktech.pos.dto.request.subscription.ChangePlanRequest;
import com.quiktech.pos.dto.request.subscription.ConfirmInvoiceRequest;
import com.quiktech.pos.dto.request.subscription.RejectInvoiceRequest;
import com.quiktech.pos.dto.response.admin.AdminStatsResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.dto.response.subscription.SubscriptionInvoiceResponse;
import com.quiktech.pos.dto.response.subscription.SubscriptionResponse;
import com.quiktech.pos.security.UserPrincipal;
import com.quiktech.pos.entity.enums.InvoiceStatus;
import com.quiktech.pos.service.AdminStatsService;
import com.quiktech.pos.service.SubscriptionService;
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
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.changePlan(businessId, request.plan(), request.billingCycle())));
    }

    @GetMapping("/invoices/pending")
    public ResponseEntity<ApiResult<Page<SubscriptionInvoiceResponse>>> listPendingInvoices(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.listPendingInvoices(pageable)));
    }

    @GetMapping("/invoices")
    public ApiResult<Page<SubscriptionInvoiceResponse>> searchInvoices(
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) Long businessId,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResult.ok(subscriptionService.searchInvoices(status, businessId, q, pageable));
    }

    @GetMapping("/invoices/pending/count")
    public ApiResult<Long> pendingCount() {
        return ApiResult.ok(subscriptionService.countPendingInvoices());
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
