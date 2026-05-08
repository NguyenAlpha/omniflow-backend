package com.omniflow.backend.controller;

import com.omniflow.backend.dto.request.store.StoreCreateRequest;
import com.omniflow.backend.dto.request.store.StoreMemberUpsertRequest;
import com.omniflow.backend.dto.response.common.ApiResult;
import com.omniflow.backend.dto.response.store.StoreMemberResponse;
import com.omniflow.backend.dto.response.store.StoreResponse;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.service.StoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @PostMapping
    public ResponseEntity<ApiResult<StoreResponse>> createStore(
            @Valid @RequestBody StoreCreateRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(storeService.createStore(request, currentUser)));
    }

    @GetMapping("/my")
    public ResponseEntity<ApiResult<List<StoreResponse>>> getMyStores(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(storeService.getMyStores(currentUser)));
    }

    @GetMapping("/{storeId}")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreResponse>> getStore(
            @PathVariable Long storeId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(storeService.getStore(storeId, currentUser)));
    }

    @PutMapping("/{storeId}")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreResponse>> updateStore(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreCreateRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(storeService.updateStore(storeId, request, currentUser)));
    }

    @GetMapping("/{storeId}/members")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<StoreMemberResponse>>> getMembers(
            @PathVariable Long storeId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(storeService.getMembers(storeId, currentUser)));
    }

    @PostMapping("/{storeId}/members")
    @PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreMemberResponse>> addMember(
            @PathVariable Long storeId,
            @Valid @RequestBody StoreMemberUpsertRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(storeService.addMember(storeId, request, currentUser)));
    }

    @PutMapping("/{storeId}/members/{memberId}")
    @PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreMemberResponse>> updateMember(
            @PathVariable Long storeId,
            @PathVariable Long memberId,
            @Valid @RequestBody StoreMemberUpsertRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResult.ok(storeService.updateMember(storeId, memberId, request, currentUser)));
    }

    @DeleteMapping("/{storeId}/members/{memberId}")
    @PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
    public ResponseEntity<ApiResult<Void>> removeMember(
            @PathVariable Long storeId,
            @PathVariable Long memberId,
            @AuthenticationPrincipal User currentUser) {
        storeService.removeMember(storeId, memberId, currentUser);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
