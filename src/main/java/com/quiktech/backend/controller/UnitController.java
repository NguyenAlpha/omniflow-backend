package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.catalog.UnitUpsertRequest;
import com.quiktech.backend.dto.response.catalog.UnitResponse;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.UnitService;
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
@RequestMapping("/api/businesses/{businessId}/units")
@RequiredArgsConstructor
public class UnitController {

    private final UnitService unitService;

    @GetMapping
    @PreAuthorize("@businessAccess.isMember(#businessId, authentication)")
    public ResponseEntity<ApiResult<List<UnitResponse>>> list(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(unitService.list(businessId, currentUser)));
    }

    @PostMapping
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<UnitResponse>> create(
            @PathVariable Long businessId,
            @Valid @RequestBody UnitUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(unitService.create(businessId, request, currentUser)));
    }

    @PatchMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<UnitResponse>> update(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @Valid @RequestBody UnitUpsertRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(unitService.update(businessId, publicId, request, currentUser)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("@businessAccess.isOwnerOrManager(#businessId, authentication)")
    public ResponseEntity<ApiResult<Void>> delete(
            @PathVariable Long businessId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        unitService.delete(businessId, publicId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
