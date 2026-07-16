package com.quiktech.backend.controller;

import com.quiktech.backend.dto.response.common.ApiResult;
import com.quiktech.backend.dto.response.notification.LowStockItemResponse;
import com.quiktech.backend.dto.response.notification.NotificationSummaryResponse;
import com.quiktech.backend.entity.enums.InvoiceStatus;
import com.quiktech.backend.repository.InventoryRepository;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.SubscriptionInvoiceRepository;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores/{storeId}/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final InventoryRepository inventoryRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final StoreRepository storeRepository;

    @GetMapping("/summary")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<NotificationSummaryResponse>> summary(@PathVariable Long storeId) {
        Long businessId = storeRepository.findBusinessIdByStoreId(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
        // COUNT ở DB thay vì load toàn bộ entity (JOIN FETCH) rồi .size()
        long lowStock = inventoryRepository.countLowStockItems(storeId);
        long pendingInvoices = invoiceRepository.countByBusinessIdAndStatus(businessId, InvoiceStatus.PENDING);
        return ResponseEntity.ok(ApiResult.ok(new NotificationSummaryResponse(lowStock, pendingInvoices)));
    }

    @GetMapping("/low-stock")
    @PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
    public ResponseEntity<ApiResult<List<LowStockItemResponse>>> lowStock(@PathVariable Long storeId) {
        Long businessId = storeRepository.findBusinessIdByStoreId(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));

        List<LowStockItemResponse> items = inventoryRepository.findLowStockItems(storeId).stream()
                .limit(10)
                .map(inv -> new LowStockItemResponse(
                        inv.getProduct().getName(),
                        inv.getProduct().getSku(),
                        inv.getQuantity(),
                        inv.getProduct().getMinStockLevel(),
                        inv.getWarehouse().getName()
                ))
                .toList();

        return ResponseEntity.ok(ApiResult.ok(items));
    }
}
