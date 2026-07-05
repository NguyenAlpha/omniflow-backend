package com.quiktech.backend.controller;

import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/stores/{storeId}/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @GetMapping("/orders")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<byte[]> exportOrders(
            @PathVariable Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        byte[] data = exportService.exportOrdersExcel(storeId, from, to);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"orders.xlsx\"")
                .body(data);
    }

    @GetMapping("/inventory")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<byte[]> exportInventory(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        byte[] data = exportService.exportInventoryExcel(storeId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"inventory.xlsx\"")
                .body(data);
    }

    @GetMapping("/purchase-orders/{publicId}/pdf")
    @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
    public ResponseEntity<byte[]> exportPurchaseOrderPdf(
            @PathVariable Long storeId,
            @PathVariable UUID publicId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        byte[] data = exportService.exportPurchaseOrderPdf(storeId, publicId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"purchase-order.pdf\"")
                .body(data);
    }
}
