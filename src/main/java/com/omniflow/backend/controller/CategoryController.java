package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.catalog.CategoryUpsertRequest;
import com.omniflow.backend.dto.response.catalog.CategoryResponse;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.security.UserPrincipal;
import com.omniflow.backend.service.CategoryService;
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
@RequestMapping("/api/stores/{storeId}/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<CategoryResponse>>> list(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(categoryService.list(storeId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<CategoryResponse>> create(
            @PathVariable Long storeId,
            @Valid @RequestBody CategoryUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(categoryService.create(storeId, request, currentUser)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<CategoryResponse>> update(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @Valid @RequestBody CategoryUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(categoryService.update(storeId, publicId, request, currentUser)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        categoryService.delete(storeId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
