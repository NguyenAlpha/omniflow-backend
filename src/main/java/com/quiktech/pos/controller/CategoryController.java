package com.quiktech.pos.controller;

import com.quiktech.pos.dto.request.catalog.CategoryUpsertRequest;
import com.quiktech.pos.dto.response.catalog.CategoryResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.security.UserPrincipal;
import com.quiktech.pos.service.CategoryService;
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
@RequestMapping("/api/businesses/{businessId}/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<List<CategoryResponse>>> list(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(categoryService.list(businessId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<CategoryResponse>> create(
            @PathVariable Long businessId,
            @Valid @RequestBody CategoryUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(categoryService.create(businessId, request, currentUser)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<CategoryResponse>> update(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @Valid @RequestBody CategoryUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(categoryService.update(businessId, publicId, request, currentUser)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        categoryService.delete(businessId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
