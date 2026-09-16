package com.quiktech.pos.controller;

import com.quiktech.pos.dto.request.business.AddBusinessMemberRequest;
import com.quiktech.pos.dto.request.business.UpdateBusinessMemberRequest;
import com.quiktech.pos.dto.response.business.BusinessMemberResponse;
import com.quiktech.pos.dto.response.common.ApiResult;
import com.quiktech.pos.service.BusinessMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Quản lý trợ lý cấp business (ROLE_BUSINESS_MANAGER). Toàn bộ owner-only — trợ lý không tự
 * thêm/bớt trợ lý khác.
 */
@RestController
@RequestMapping("/api/businesses/{businessId}/members")
@RequiredArgsConstructor
public class BusinessMemberController {

    private final BusinessMemberService businessMemberService;

    @GetMapping
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<List<BusinessMemberResponse>>> getMembers(@PathVariable Long businessId) {
        return ResponseEntity.ok(ApiResult.ok(businessMemberService.getMembers(businessId)));
    }

    @PostMapping
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<BusinessMemberResponse>> addMember(
            @PathVariable Long businessId,
            @Valid @RequestBody AddBusinessMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(businessMemberService.addMember(businessId, request)));
    }

    @PatchMapping("/{memberId}")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<BusinessMemberResponse>> updateMember(
            @PathVariable Long businessId,
            @PathVariable Long memberId,
            @Valid @RequestBody UpdateBusinessMemberRequest request) {
        return ResponseEntity.ok(ApiResult.ok(businessMemberService.updateMember(businessId, memberId, request)));
    }

    @DeleteMapping("/{memberId}")
    @PreAuthorize("@businessAccess.isOwner(#businessId, authentication)")
    public ResponseEntity<ApiResult<Void>> removeMember(
            @PathVariable Long businessId,
            @PathVariable Long memberId) {
        businessMemberService.removeMember(businessId, memberId);
        return ResponseEntity.ok(ApiResult.ok());
    }
}
