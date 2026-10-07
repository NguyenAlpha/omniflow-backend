package com.quiktech.pos.controller;

import com.quiktech.pos.dto.request.subscription.PaymentAccountRequest;
import com.quiktech.pos.dto.request.subscription.PaymentAccountActionRequest;
import com.quiktech.pos.dto.response.admin.PaymentAccountResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.service.SubscriptionPaymentAccountService;
import com.quiktech.pos.service.PaymentQrStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import java.io.IOException;
import java.util.Map;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/payment-accounts")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminPaymentAccountController {
    private final SubscriptionPaymentAccountService service;
    private final PaymentQrStorage qrStorage;

    @PostMapping(value = "/qr", consumes = {"image/png", "image/jpeg"})
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> uploadQr(HttpServletRequest request) throws IOException {
        String key = qrStorage.upload(request.getInputStream());
        return ApiResult.ok(Map.of("qrImageKey", key, "qrImageUrl", PaymentQrStorage.adminUrl(key)));
    }

    @GetMapping("/qr/{key}")
    public ResponseEntity<Resource> getQr(@PathVariable String key) {
        return qrStorage.image(key);
    }

    @GetMapping
    public ApiResult<List<PaymentAccountResponse>> list() { return ApiResult.ok(service.list()); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<PaymentAccountResponse> create(@Valid @RequestBody PaymentAccountRequest request) {
        return ApiResult.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResult<PaymentAccountResponse> update(@PathVariable Long id, @Valid @RequestBody PaymentAccountRequest request) {
        return ApiResult.ok(service.update(id, request));
    }

    @PostMapping("/{id}/activate")
    public ApiResult<PaymentAccountResponse> activate(@PathVariable Long id, @Valid @RequestBody PaymentAccountActionRequest request) {
        return ApiResult.ok(service.activate(id, request));
    }

    @PostMapping("/{id}/archive")
    public ApiResult<PaymentAccountResponse> archive(@PathVariable Long id, @Valid @RequestBody PaymentAccountActionRequest request) {
        return ApiResult.ok(service.archive(id, request));
    }
}
