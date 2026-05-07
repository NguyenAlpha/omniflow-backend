package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.catalog.UnitUpsertRequest;
import com.omniflow.backend.dto.response.catalog.UnitResponse;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.service.UnitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/stores/{storeId}/units")
@RequiredArgsConstructor
public class UnitController {

    private final UnitService unitService;

    @GetMapping
    public ResponseEntity<ApiResult<List<UnitResponse>>> list(
            @PathVariable Long storeId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(unitService.list(storeId, currentUser)));
    }

    @PostMapping
    public ResponseEntity<ApiResult<UnitResponse>> create(
            @PathVariable Long storeId,
            @Valid @RequestBody UnitUpsertRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(unitService.create(storeId, request, currentUser)));
    }

    @PutMapping("/{publicId}")
    public ResponseEntity<ApiResult<UnitResponse>> update(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @Valid @RequestBody UnitUpsertRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(unitService.update(storeId, publicId, request, currentUser)));
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        unitService.delete(storeId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
