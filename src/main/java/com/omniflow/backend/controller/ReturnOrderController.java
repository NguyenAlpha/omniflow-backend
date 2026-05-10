package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.order.ReturnOrderCreateRequest;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.dto.response.order.ReturnOrderResponse;
import com.omniflow.backend.security.UserPrincipal;
import com.omniflow.backend.service.ReturnOrderService;
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
@RequestMapping("/api/stores/{storeId}/returns")
@RequiredArgsConstructor
public class ReturnOrderController {

    private final ReturnOrderService returnOrderService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<ReturnOrderResponse>>> list(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(returnOrderService.list(storeId, currentUser)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<ReturnOrderResponse>> get(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(returnOrderService.get(storeId, publicId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<ReturnOrderResponse>> create(
            @PathVariable Long storeId,
            @Valid @RequestBody ReturnOrderCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(returnOrderService.create(storeId, request, currentUser)));
    }

    @PutMapping("/{publicId}/complete")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<ReturnOrderResponse>> complete(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(returnOrderService.complete(storeId, publicId, currentUser)));
    }

    @PutMapping("/{publicId}/cancel")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<ReturnOrderResponse>> cancel(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(returnOrderService.cancel(storeId, publicId, currentUser)));
    }
}
