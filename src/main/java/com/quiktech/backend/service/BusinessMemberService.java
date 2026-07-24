package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.business.AddBusinessMemberRequest;
import com.quiktech.backend.dto.request.business.UpdateBusinessMemberRequest;
import com.quiktech.backend.dto.response.business.BusinessMemberResponse;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.Business;
import com.quiktech.backend.entity.BusinessMember;
import com.quiktech.backend.entity.Role;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.entity.UserRole;
import com.quiktech.backend.entity.enums.RoleName;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.BusinessMemberRepository;
import com.quiktech.backend.repository.BusinessRepository;
import com.quiktech.backend.repository.RoleRepository;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.repository.UserRoleRepository;
import com.quiktech.backend.security.BusinessAccessEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Quản lý "trợ lý cấp business" (ROLE_BUSINESS_MANAGER) — người quản lý mọi store trong
 * business nhưng không đụng billing/hồ sơ business. Mirror {@link StoreService} member methods.
 *
 * <p>Mỗi thao tác ghi cặp {@link BusinessMember} (roster) + {@link UserRole} business-scoped
 * (business_id set, store_id NULL). Access control đọc {@code UserRole}; roster là organizational data.
 * Endpoint là owner-only (xem {@code @PreAuthorize} ở controller).
 */
@Service
@RequiredArgsConstructor
public class BusinessMemberService {

    private final BusinessRepository businessRepository;
    private final BusinessMemberRepository businessMemberRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final BusinessAccessEvaluator businessAccessEvaluator;
    private final SubscriptionLimitService subscriptionLimitService;

    @Transactional(readOnly = true)
    public List<BusinessMemberResponse> getMembers(Long businessId) {
        findBusinessOrThrow(businessId);

        List<BusinessMember> members = businessMemberRepository
                .findByBusinessIdAndIsActiveAndDeletedAtIsNull(businessId, true);
        Map<Long, UserRole> roleByUserId = userRoleRepository
                .findByBusinessIdAndIsActiveTrueAndDeletedAtIsNull(businessId)
                .stream()
                // merge (a, b) -> a: phòng thủ nếu dữ liệu bẩn có 2 business role active cùng user
                .collect(Collectors.toMap(ur -> ur.getUser().getId(), ur -> ur, (a, b) -> a));

        return members.stream()
                .map(m -> toResponse(m, roleByUserId.get(m.getUser().getId())))
                .toList();
    }

    @Transactional
    public BusinessMemberResponse addMember(Long businessId, AddBusinessMemberRequest request) {
        Business business = findBusinessOrThrow(businessId);

        if (businessMemberRepository.findActiveBusinessMember(businessId, request.userId()).isPresent()) {
            throw new IllegalArgumentException("User is already a member of this business");
        }

        User targetUser = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Trợ lý tính vào quota max_staff (cùng bể với nhân viên store) — chặn lách giới hạn gói
        subscriptionLimitService.checkStaffLimit(businessId);

        BusinessMember member = BusinessMember.builder()
                .user(targetUser)
                .business(business)
                .joinedDate(LocalDate.now())
                .isActive(request.isActive())
                .publicId(UUID.randomUUID())
                .build();
        businessMemberRepository.save(member);

        UserRole userRole = UserRole.builder()
                .user(targetUser)
                .role(findRoleOrThrow(RoleName.ROLE_BUSINESS_MANAGER))
                .business(business)
                .isActive(request.isActive())
                .build();
        userRoleRepository.save(userRole);

        evictBusinessRoleCacheAfterCommit(request.userId(), businessId);

        return toResponse(member, userRole);
    }

    @Transactional
    public BusinessMemberResponse updateMember(Long businessId, Long memberId, UpdateBusinessMemberRequest request) {
        findBusinessOrThrow(businessId);

        // Scoped theo businessId — chống IDOR xuyên tenant (xem Javadoc repository method)
        BusinessMember member = businessMemberRepository.findByIdAndBusinessIdAndDeletedAtIsNull(memberId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findActiveBusinessRole(member.getUser().getId(), businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_MEMBER_NOT_FOUND, "Member role not found"));

        requireNotOwner(userRole);

        userRole.setIsActive(request.isActive());
        userRoleRepository.save(userRole);

        member.setIsActive(request.isActive());
        businessMemberRepository.save(member);

        evictBusinessRoleCacheAfterCommit(member.getUser().getId(), businessId);

        return toResponse(member, userRole);
    }

    @Transactional
    public void removeMember(Long businessId, Long memberId) {
        findBusinessOrThrow(businessId);

        BusinessMember member = businessMemberRepository.findByIdAndBusinessIdAndDeletedAtIsNull(memberId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findActiveBusinessRole(member.getUser().getId(), businessId)
                .orElse(null);

        if (userRole != null) requireNotOwner(userRole);

        Instant now = Instant.now();
        member.setDeletedAt(now);
        businessMemberRepository.save(member);

        if (userRole != null) {
            userRole.setDeletedAt(now);
            userRoleRepository.save(userRole);
        }

        evictBusinessRoleCacheAfterCommit(member.getUser().getId(), businessId);
    }

    /**
     * Endpoint này chỉ quản lý trợ lý (BUSINESS_MANAGER). Không cho phép sửa/xóa OWNER qua đây —
     * OWNER được cấp lúc tạo business, đổi chủ là luồng nghiệp vụ riêng.
     */
    private void requireNotOwner(UserRole userRole) {
        if (userRole.getRole().getName() == RoleName.ROLE_OWNER) {
            throw new IllegalArgumentException("Cannot manage the business owner via business member endpoint");
        }
    }

    /**
     * Evict cache business role SAU KHI commit — nếu evict giữa transaction, request khác chen vào
     * (sau evict, trước commit) sẽ cache-miss → đọc DB thấy role cũ (chưa commit) → hồi sinh cache
     * tới hết TTL. Xem {@code StoreService.evictMemberCachesAfterCommit}.
     */
    private void evictBusinessRoleCacheAfterCommit(Long userId, Long businessId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                businessAccessEvaluator.evictBusinessRoleCache(userId, businessId);
            }
        });
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    private Role findRoleOrThrow(RoleName roleName) {
        return roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role not found: " + roleName));
    }

    private BusinessMemberResponse toResponse(BusinessMember m, UserRole userRole) {
        return new BusinessMemberResponse(
                m.getId(), m.getPublicId(),
                m.getUser().getId(), m.getUser().getUsername(),
                m.getBusiness().getId(),
                userRole != null ? userRole.getRole().getName() : null,
                m.getJoinedDate(),
                m.getIsActive(), m.getSyncVersion(), m.getLastModifiedAt()
        );
    }
}
