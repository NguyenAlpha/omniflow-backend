package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.order.OrderCreateRequest;
import com.quiktech.backend.dto.request.order.OrderPayRequest;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.order.OrderResponse;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.OrderService;
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

import java.time.LocalDate;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/stores/{storeId}/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<PagedResult<OrderResponse>>> list(
            @PathVariable Long storeId,
            @RequestParam(required = false) String orderCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID customerPublicId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        var pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResult.ok(orderService.list(storeId, orderCode, status, from, to, customerPublicId, pageable, currentUser)));
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

    @PutMapping("/{publicId}/pay")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<OrderResponse>> pay(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @Valid @RequestBody OrderPayRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(orderService.pay(storeId, publicId, request.amount(), currentUser)));
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
