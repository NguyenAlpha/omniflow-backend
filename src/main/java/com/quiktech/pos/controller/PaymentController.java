package com.quiktech.pos.controller;

import com.quiktech.pos.dto.request.payment.PaymentCreateRequest;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.dto.response.payment.PaymentPageResult;
import com.quiktech.pos.dto.response.payment.PaymentResponse;
import com.quiktech.pos.security.UserPrincipal;
import com.quiktech.pos.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/stores/{storeId}/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<PaymentPageResult>> search(
            @PathVariable Long storeId,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResult.ok(paymentService.search(storeId, direction, method, from, to, pageable)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<PaymentResponse>> get(
            @PathVariable Long storeId,
            @PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResult.ok(paymentService.get(storeId, publicId)));
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

    @DeleteMapping("/{publicId}")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long storeId,
            @PathVariable UUID publicId) {
        paymentService.delete(storeId, publicId);
        return ResponseEntity.ok(ApiResult.ok(null));
    }
}
