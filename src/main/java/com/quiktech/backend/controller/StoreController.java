package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.common.SetStatusRequest;
import com.quiktech.backend.dto.request.store.StoreCreateRequest;
import com.quiktech.backend.dto.request.store.AddMemberRequest;
import com.quiktech.backend.dto.request.store.UpdateMemberRequest;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.store.StoreMemberResponse;
import com.quiktech.backend.dto.response.store.StoreResponse;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.StoreService;
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
    public ResponseEntity<ApiResult<StoreResponse>> createStore(@Valid @RequestBody StoreCreateRequest request, @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(storeService.createStore(request, currentUser)));
    }

    @GetMapping
    public ResponseEntity<ApiResult<List<StoreResponse>>> getStores(@AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(storeService.getStores(currentUser)));
    }

    @GetMapping("/{storeId}")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreResponse>> getStore(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResult.ok(storeService.getStore(storeId)));
    }

    @PatchMapping("/{storeId}")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreResponse>> updateStore(@PathVariable Long storeId, @Valid @RequestBody StoreCreateRequest request) {
        return ResponseEntity.ok(ApiResult.ok(storeService.updateStore(storeId, request)));
    }

    @PatchMapping("/{storeId}/status")
    @PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreResponse>> setStoreStatus(@PathVariable Long storeId, @Valid @RequestBody SetStatusRequest request) {
        return ResponseEntity.ok(ApiResult.ok(storeService.setStoreStatus(storeId, request.isActive())));
    }

    @GetMapping("/{storeId}/members")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<StoreMemberResponse>>> getMembers(@PathVariable Long storeId) {
        return ResponseEntity.ok(ApiResult.ok(storeService.getMembers(storeId)));
    }

    @PostMapping("/{storeId}/members")
    @PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreMemberResponse>> addMember(@PathVariable Long storeId, @Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(storeService.addMember(storeId, request)));
    }

    @PatchMapping("/{storeId}/members/{memberId}")
    @PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
    public ResponseEntity<ApiResult<StoreMemberResponse>> updateMember(@PathVariable Long storeId, @PathVariable Long memberId, @Valid @RequestBody UpdateMemberRequest request, @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(storeService.updateMember(storeId, memberId, request, currentUser)));
    }

    @DeleteMapping("/{storeId}/members/{memberId}")
    @PreAuthorize("@storeAccess.isOwner(#storeId, authentication)")
    public ResponseEntity<ApiResult<Void>> removeMember(@PathVariable Long storeId, @PathVariable Long memberId) {
        storeService.removeMember(storeId, memberId);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
