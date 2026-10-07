package com.quiktech.pos.controller;

import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.dto.response.notification.LowStockItemResponse;
import com.quiktech.pos.dto.response.notification.NotificationSummaryResponse;
import com.quiktech.pos.dto.response.notification.NotificationPageResponse;
import com.quiktech.pos.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores/{storeId}/notifications")
@PreAuthorize("@storeAccess.isMember(#storeId, authentication)")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping("/summary")
    public ApiResult<NotificationSummaryResponse> summary(@PathVariable Long storeId, Authentication auth) {
        return ApiResult.ok(service.summary(storeId, auth));
    }

    @GetMapping
    public ApiResult<NotificationPageResponse> list(@PathVariable Long storeId, Authentication auth,
            @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ApiResult.ok(service.list(storeId, auth, cursor, size, unreadOnly));
    }

    @GetMapping("/low-stock")
    public ApiResult<List<LowStockItemResponse>> lowStock(@PathVariable Long storeId, Authentication auth) {
        return ApiResult.ok(service.lowStock(storeId, auth));
    }

    @PostMapping("/{id}/read")
    public ApiResult<Void> markRead(@PathVariable Long storeId, @PathVariable Long id, Authentication auth) {
        service.markRead(storeId, id, auth);
        return ApiResult.ok();
    }

    public record ReadAllRequest(@NotNull @PositiveOrZero Long throughId) {}

    @PostMapping("/read-all")
    public ApiResult<Void> markAllRead(@PathVariable Long storeId, @Valid @RequestBody ReadAllRequest request, Authentication auth) {
        service.markAllRead(storeId, request.throughId(), auth);
        return ApiResult.ok();
    }
}
