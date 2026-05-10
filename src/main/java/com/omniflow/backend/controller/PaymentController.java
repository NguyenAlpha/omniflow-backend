package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.payment.PaymentCreateRequest;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.dto.response.payment.PaymentResponse;
import com.omniflow.backend.security.UserPrincipal;
import com.omniflow.backend.service.PaymentService;
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
@RequestMapping("/api/stores/{storeId}/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<PaymentResponse>>> list(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(paymentService.list(storeId, currentUser)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<PaymentResponse>> get(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(paymentService.get(storeId, publicId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<PaymentResponse>> create(
            @PathVariable Long storeId,
            @Valid @RequestBody PaymentCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(paymentService.create(storeId, request, currentUser)));
    }
}
