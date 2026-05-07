package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.catalog.ProductUpsertRequest;
import com.omniflow.backend.dto.response.catalog.ProductResponse;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.dto.response.common.PagedResult;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/stores/{storeId}/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<ApiResult<List<ProductResponse>>> list(
            @PathVariable Long storeId,
            @RequestParam(required = false) Boolean isActive,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.list(storeId, isActive, currentUser)));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResult<PagedResult<ProductResponse>>> search(
            @PathVariable Long storeId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal User currentUser) {
        var pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        return ResponseEntity.ok(ApiResult.ok(productService.search(storeId, q, pageable, currentUser)));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResult<ProductResponse>> get(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.get(storeId, publicId, currentUser)));
    }

    @PostMapping
    public ResponseEntity<ApiResult<ProductResponse>> create(
            @PathVariable Long storeId,
            @Valid @RequestBody ProductUpsertRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(productService.create(storeId, request, currentUser)));
    }

    @PutMapping("/{publicId}")
    public ResponseEntity<ApiResult<ProductResponse>> update(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @Valid @RequestBody ProductUpsertRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(productService.update(storeId, publicId, request, currentUser)));
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        productService.delete(storeId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
