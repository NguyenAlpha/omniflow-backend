package com.quiktech.backend.controller;

import com.quiktech.backend.dto.request.inventory.InventoryAdjustRequest;
import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.inventory.InventoryResponse;
import com.quiktech.backend.dto.response.inventory.InventoryTransactionResponse;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/stores/{storeId}/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<InventoryResponse>>> list(
            @PathVariable Long storeId,
            @RequestParam(required = false) UUID warehousePublicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        List<InventoryResponse> result = warehousePublicId != null
                ? inventoryService.listByWarehouse(storeId, warehousePublicId, currentUser)
                : inventoryService.list(storeId, currentUser);
        return ResponseEntity.ok(ApiResult.ok(result));
    }

    @GetMapping("/transactions")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<InventoryTransactionResponse>>> listTransactions(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(inventoryService.listTransactions(storeId, currentUser)));
    }

    @PostMapping("/adjust")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<ApiResult<InventoryTransactionResponse>> adjust(
            @PathVariable Long storeId,
            @Valid @RequestBody InventoryAdjustRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(ApiResult.ok(inventoryService.adjust(storeId, request, currentUser)));
    }
}
