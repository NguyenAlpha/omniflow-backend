package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.catalog.CategoryUpsertRequest;
import com.omniflow.backend.dto.response.catalog.CategoryResponse;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ApiResult<List<CategoryResponse>>> list(
            @PathVariable Long storeId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(categoryService.list(storeId, currentUser)));
    }

    @PostMapping
    public ResponseEntity<ApiResult<CategoryResponse>> create(
            @PathVariable Long storeId,
            @Valid @RequestBody CategoryUpsertRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(categoryService.create(storeId, request, currentUser)));
    }

    @PutMapping("/{publicId}")
    public ResponseEntity<ApiResult<CategoryResponse>> update(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @Valid @RequestBody CategoryUpsertRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(categoryService.update(storeId, publicId, request, currentUser)));
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        categoryService.delete(storeId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
