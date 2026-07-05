package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.partner.SupplierPayRequest;
import com.quiktech.backend.dto.request.partner.SupplierUpsertRequest;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.partner.SupplierResponse;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.SupplierService;
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
@RequestMapping("/api/businesses/{businessId}/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @GetMapping
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<List<SupplierResponse>>> list(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(supplierService.list(businessId, currentUser)));
    }

    @GetMapping("/search")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<PagedResult<SupplierResponse>>> search(
            @PathVariable Long businessId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        var pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return ResponseEntity.ok(ApiResult.ok(supplierService.search(businessId, q, pageable, currentUser)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<SupplierResponse>> get(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(supplierService.get(businessId, publicId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<SupplierResponse>> create(
            @PathVariable Long businessId,
            @Valid @RequestBody SupplierUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(supplierService.create(businessId, request, currentUser)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<SupplierResponse>> update(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @Valid @RequestBody SupplierUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(supplierService.update(businessId, publicId, request, currentUser)));
    }

    @PutMapping("/{publicId}/pay")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<SupplierResponse>> pay(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @Valid @RequestBody SupplierPayRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(supplierService.pay(businessId, publicId, request, currentUser)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        supplierService.delete(businessId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
