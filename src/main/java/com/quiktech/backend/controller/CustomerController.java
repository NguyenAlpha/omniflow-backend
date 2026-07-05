package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.partner.CustomerPayRequest;
import com.quiktech.backend.dto.request.partner.CustomerUpsertRequest;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.partner.CustomerResponse;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.CustomerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/businesses/{businessId}/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<List<CustomerResponse>>> list(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(customerService.list(businessId, currentUser)));
    }

    @GetMapping("/search")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<PagedResult<CustomerResponse>>> search(
            @PathVariable Long businessId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        var pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return ResponseEntity.ok(ApiResult.ok(customerService.search(businessId, q, pageable, currentUser)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<CustomerResponse>> get(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(customerService.get(businessId, publicId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<CustomerResponse>> create(
            @PathVariable Long businessId,
            @Valid @RequestBody CustomerUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(customerService.create(businessId, request, currentUser)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<CustomerResponse>> update(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @Valid @RequestBody CustomerUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(customerService.update(businessId, publicId, request, currentUser)));
    }

    @PutMapping("/{publicId}/pay")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<CustomerResponse>> pay(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @Valid @RequestBody CustomerPayRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(customerService.pay(businessId, publicId, request, currentUser)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        customerService.delete(businessId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
