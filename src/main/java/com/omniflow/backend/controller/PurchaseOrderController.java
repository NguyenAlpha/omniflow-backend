package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.purchase.PurchaseOrderCreateRequest;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.dto.response.purchase.PurchaseOrderResponse;
import com.omniflow.backend.security.UserPrincipal;
import com.omniflow.backend.service.PurchaseOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/stores/{storeId}/purchases")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<PurchaseOrderResponse>>> list(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(purchaseOrderService.list(storeId, currentUser)));
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

    @PutMapping("/{publicId}/cancel")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<PurchaseOrderResponse>> cancel(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(purchaseOrderService.cancel(storeId, publicId, currentUser)));
    }
}
