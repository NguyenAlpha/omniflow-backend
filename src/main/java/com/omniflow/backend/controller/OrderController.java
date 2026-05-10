package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.order.OrderCreateRequest;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.dto.response.order.OrderResponse;
import com.omniflow.backend.security.UserPrincipal;
import com.omniflow.backend.service.OrderService;
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
@RequestMapping("/api/stores/{storeId}/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<OrderResponse>>> list(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(orderService.list(storeId, currentUser)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<OrderResponse>> get(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(orderService.get(storeId, publicId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<OrderResponse>> create(
            @PathVariable Long storeId,
            @Valid @RequestBody OrderCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(orderService.create(storeId, request, currentUser)));
    }

    @PutMapping("/{publicId}/complete")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<OrderResponse>> complete(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(orderService.complete(storeId, publicId, currentUser)));
    }

    @PutMapping("/{publicId}/cancel")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<OrderResponse>> cancel(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(orderService.cancel(storeId, publicId, currentUser)));
    }
}
