package com.quiktech.pos.controller;

import com.quiktech.pos.dto.request.catalog.ProductUpsertRequest;
import com.quiktech.pos.dto.request.common.SetStatusRequest;
import com.quiktech.pos.dto.response.catalog.ProductDetailResponse;
import com.quiktech.pos.dto.response.catalog.ProductImportResponse;
import com.quiktech.pos.dto.response.catalog.ProductLimitResponse;
import com.quiktech.pos.dto.response.catalog.ProductResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.dto.response.common.PagedResult;
import com.quiktech.pos.security.UserPrincipal;
import com.quiktech.pos.service.ProductService;
import com.quiktech.pos.service.SubscriptionLimitService;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/businesses/{businessId}/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final SubscriptionLimitService subscriptionLimitService;

    @GetMapping
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<List<ProductResponse>>> list(
            @PathVariable Long businessId,
            @RequestParam(required = false) Boolean isActive,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.list(businessId, isActive, currentUser)));
    }

    @GetMapping("/search")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<PagedResult<ProductResponse>>> search(
            @PathVariable Long businessId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) UUID categoryPublicId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sort,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        String field = "name".equals(sortBy) ? "name" : "updatedAt";
        Sort.Direction direction = "asc".equalsIgnoreCase(sort) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(page, size, Sort.by(direction, field));
        return ResponseEntity.ok(ApiResult.ok(productService.search(businessId, q, isActive, categoryPublicId, pageable, currentUser)));
    }

    // Cùng quyền với POST create — UI gọi trước khi mở form tạo product
    @GetMapping("/limit")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<ProductLimitResponse>> getLimit(@PathVariable Long businessId) {
        return ResponseEntity.ok(ApiResult.ok(subscriptionLimitService.getProductLimit(businessId)));
    }

    @GetMapping("/sku/{sku}")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<ProductResponse>> getBySku(
            @PathVariable Long businessId,
            @PathVariable String sku,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.findBySku(businessId, sku, currentUser)));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<ProductDetailResponse>> get(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.get(businessId, publicId, currentUser)));
    }

    @PostMapping("/import")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<ProductImportResponse>> importCsv(
            @PathVariable Long businessId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.importCsv(businessId, file, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<ProductResponse>> create(
            @PathVariable Long businessId,
            @Valid @RequestBody ProductUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(productService.create(businessId, request, currentUser)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<ProductResponse>> update(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @Valid @RequestBody ProductUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.update(businessId, publicId, request, currentUser)));
    }

    @PatchMapping("/{publicId}/status")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<ProductResponse>> setStatus(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @Valid @RequestBody SetStatusRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.setStatus(businessId, publicId, request.isActive(), currentUser)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        productService.delete(businessId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
