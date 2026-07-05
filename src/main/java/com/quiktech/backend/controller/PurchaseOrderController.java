package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.purchase.PurchaseOrderCreateRequest;
import com.quiktech.backend.dto.request.purchase.PurchaseOrderPayRequest;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.purchase.PurchaseOrderResponse;
import com.quiktech.backend.entity.enums.PurchaseOrderStatus;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.PurchaseOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/stores/{storeId}/purchases")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<PagedResult<PurchaseOrderResponse>>> list(
            @PathVariable Long storeId,
            @RequestParam(required = false) String orderCode,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        var pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResult.ok(purchaseOrderService.list(storeId, orderCode, status, from, to, pageable, currentUser)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<PurchaseOrderResponse>> get(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(purchaseOrderService.get(storeId, publicId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<PurchaseOrderResponse>> create(
            @PathVariable Long storeId,
            @Valid @RequestBody PurchaseOrderCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(purchaseOrderService.create(storeId, request, currentUser)));
    }

    @PutMapping("/{publicId}/receive")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<PurchaseOrderResponse>> receive(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(purchaseOrderService.receive(storeId, publicId, currentUser)));
    }

    @PutMapping("/{publicId}/pay")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<PurchaseOrderResponse>> pay(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @Valid @RequestBody PurchaseOrderPayRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(purchaseOrderService.pay(storeId, publicId, request.amount(), currentUser)));
    }

    @PutMapping("/{publicId}/cancel")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<PurchaseOrderResponse>> cancel(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(purchaseOrderService.cancel(storeId, publicId, currentUser)));
    }
}
