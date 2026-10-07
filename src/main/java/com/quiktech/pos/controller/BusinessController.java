package com.quiktech.pos.controller;

import com.quiktech.pos.dto.request.business.BusinessCreateRequest;
import com.quiktech.pos.dto.request.common.SetStatusRequest;
import com.quiktech.pos.dto.request.subscription.DowngradeRequest;
import com.quiktech.pos.dto.request.subscription.SubmitPaymentRefRequest;
import com.quiktech.pos.dto.request.subscription.UpgradeRequest;
import com.quiktech.pos.dto.response.business.BusinessDefaultResponse;
import com.quiktech.pos.dto.response.business.BusinessResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.dto.response.subscription.BankTransferInfoResponse;
import com.quiktech.pos.dto.response.subscription.SubscriptionInvoiceResponse;
import com.quiktech.pos.dto.response.subscription.SubscriptionResponse;
import com.quiktech.pos.dto.response.subscription.UpgradeResponse;
import com.quiktech.pos.security.UserPrincipal;
import com.quiktech.pos.service.BusinessService;
import com.quiktech.pos.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/businesses")
@RequiredArgsConstructor
public class BusinessController {

    private final BusinessService businessService;
    private final SubscriptionService subscriptionService;

    @PostMapping("/default")
    public ResponseEntity<ApiResult<BusinessDefaultResponse>> createDefaultBusiness(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(businessService.createDefaultBusiness(currentUser)));
    }

    @PostMapping
    public ResponseEntity<ApiResult<BusinessResponse>> createBusiness(
            @Valid @RequestBody BusinessCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(businessService.createBusiness(request, currentUser)));
    }

    @GetMapping
    public ResponseEntity<ApiResult<List<BusinessResponse>>> getBusinesses(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(businessService.getBusinesses(currentUser)));
    }

    @GetMapping("/{businessId}")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<BusinessResponse>> getBusiness(@PathVariable Long businessId) {
        return ResponseEntity.ok(ApiResult.ok(businessService.getBusiness(businessId)));
    }

    @GetMapping("/{businessId}/subscription")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<SubscriptionResponse>> getSubscription(@PathVariable Long businessId) {
        return ResponseEntity.ok(ApiResult.ok(businessService.getSubscription(businessId)));
    }

    @PostMapping("/{businessId}/subscription/upgrade")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<UpgradeResponse>> requestUpgrade(
            @PathVariable Long businessId,
            @Valid @RequestBody UpgradeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(subscriptionService.requestUpgrade(businessId, request.plan(), request.billingCycle())));
    }

    @PostMapping("/{businessId}/subscription/downgrade")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<SubscriptionResponse>> scheduleDowngrade(
            @PathVariable Long businessId,
            @Valid @RequestBody DowngradeRequest request) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.scheduleDowngrade(businessId, request.plan())));
    }

    @DeleteMapping("/{businessId}/subscription/downgrade")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<SubscriptionResponse>> cancelScheduledDowngrade(
            @PathVariable Long businessId) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.cancelScheduledDowngrade(businessId)));
    }

    @PatchMapping("/{businessId}/subscription/invoices/{invoiceId}/payment")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<SubscriptionInvoiceResponse>> submitPaymentRef(
            @PathVariable Long businessId,
            @PathVariable Long invoiceId,
            @Valid @RequestBody SubmitPaymentRefRequest request) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.submitPaymentRef(businessId, invoiceId, request.bankTransferRef())));
    }

    @GetMapping("/{businessId}/subscription/invoices")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<Page<SubscriptionInvoiceResponse>>> getInvoices(
            @PathVariable Long businessId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.getInvoices(businessId, pageable)));
    }

    @GetMapping("/{businessId}/subscription/invoices/{invoiceId}")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<SubscriptionInvoiceResponse>> getInvoice(
            @PathVariable Long businessId,
            @PathVariable Long invoiceId) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionService.getInvoice(businessId, invoiceId)));
    }

    @DeleteMapping("/{businessId}/subscription/invoices/{invoiceId}")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<Void> cancelInvoice(
            @PathVariable Long businessId,
            @PathVariable Long invoiceId) {
        subscriptionService.cancelInvoice(businessId, invoiceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{businessId}/subscription/bank-info")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<BankTransferInfoResponse>> getBankInfo(@PathVariable Long businessId,
            @RequestParam(required = false) Long invoiceId) {
        return ResponseEntity.ok(ApiResult.ok(invoiceId == null ? subscriptionService.getBankTransferInfo()
                : subscriptionService.getInvoiceBankTransferInfo(businessId, invoiceId)));
    }

    @GetMapping("/{businessId}/subscription/invoices/{invoiceId}/qr")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<Resource> getInvoiceQr(
            @PathVariable Long businessId, @PathVariable Long invoiceId) {
        return subscriptionService.getInvoiceQr(businessId, invoiceId);
    }

    @PatchMapping("/{businessId}")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<BusinessResponse>> updateBusiness(
            @PathVariable Long businessId,
            @Valid @RequestBody BusinessCreateRequest request) {
        return ResponseEntity.ok(ApiResult.ok(businessService.updateBusiness(businessId, request)));
    }

    @PatchMapping("/{businessId}/status")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<BusinessResponse>> setBusinessStatus(
            @PathVariable Long businessId,
            @Valid @RequestBody SetStatusRequest request) {
        return ResponseEntity.ok(ApiResult.ok(businessService.setBusinessStatus(businessId, request.isActive())));
    }
}
