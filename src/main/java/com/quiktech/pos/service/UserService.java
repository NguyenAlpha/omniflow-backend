package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.user.ChangePasswordRequest;
import com.quiktech.pos.dto.request.user.SetUserStatusRequest;
import com.quiktech.pos.dto.request.user.UpdateProfileRequest;
import com.quiktech.pos.dto.response.auth.BusinessMembershipResponse;
import com.quiktech.pos.dto.response.auth.UserSummaryResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.common.PagedResult;
import com.quiktech.pos.dto.response.user.UserAdminResponse;
import com.quiktech.pos.dto.response.user.UserLookupResponse;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.exception.BusinessRuleException;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import com.quiktech.pos.security.BusinessAccessEvaluator;
import com.quiktech.pos.security.StoreAccessEvaluator;
import com.quiktech.pos.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final StoreAccessEvaluator storeAccessEvaluator;
    private final BusinessAccessEvaluator businessAccessEvaluator;
    private final AdminAuditService adminAuditService;
    private final AuthResponseAssembler authResponseAssembler;

    @Transactional(readOnly = true)
    public UserSummaryResponse getProfile(UserPrincipal currentUser) {
        User user = findOrThrow(currentUser.userId());
        return toResponse(user);
    }

    /**
     * Danh sách business/store hiện tại của user — cùng logic với memberships trong response
     * đăng nhập, để client đồng bộ lại khi có thay đổi sau lúc login (tạo store mới, được thêm
     * vào store khác, đổi role, store bị xóa...).
     */
    @Transactional(readOnly = true)
    public List<BusinessMembershipResponse> getMemberships(UserPrincipal currentUser) {
        return authResponseAssembler.resolveMemberships(findOrThrow(currentUser.userId()));
    }

    @Transactional
    public UserSummaryResponse updateProfile(UserPrincipal currentUser, UpdateProfileRequest request) {
        User user = findOrThrow(currentUser.userId());
        checkUsernameAndEmailUnique(request.username(), request.email(), user.getId());
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setPhone(request.phone());
        user.setUpdatedAt(Instant.now());
        // saveAndFlush + catch: 2 request song song cùng pass checkUsernameAndEmailUnique
        // (TOCTOU) — unique index DB chặn request thua, convert 500 → 400 như register.
        // Phải flush ngay trong try; để flush lúc commit thì exception thoát ra ngoài catch.
        try {
            return toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Username or email already taken");
        }
    }

    @Transactional
    public void changePassword(UserPrincipal currentUser, ChangePasswordRequest request) {
        User user = findOrThrow(currentUser.userId());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessRuleException(ErrorCode.INVALID_CURRENT_PASSWORD, "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        // Đổi mật khẩu = thu hồi toàn bộ refresh token: nếu kẻ tấn công đang giữ
        // refresh token cũ (lý do khiến user đổi mật khẩu) thì phiên đó bị cắt ngay,
        // không thể tự gia hạn tiếp. Access token cũ vẫn sống tối đa jwt.expiration.
        refreshTokenService.revokeAll(user.getId());
    }

    /**
     * Tra cứu user theo username (khớp chính xác) để owner lấy userId khi thêm thành viên.
     * Chỉ khớp user còn sống (@SQLRestriction lọc deleted). Không tìm thấy → 404.
     */
    @Transactional(readOnly = true)
    public UserLookupResponse lookupByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User not found"));
        return new UserLookupResponse(user.getId(), user.getUsername(), user.getFullName(), user.getIsActive());
    }

    // === Admin ===

    @Transactional
    public UserAdminResponse updateUser(Long userId, UpdateProfileRequest request) {
        User user = findOrThrow(userId);
        checkUsernameAndEmailUnique(request.username(), request.email(), userId);
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setPhone(request.phone());
        user.setUpdatedAt(Instant.now());
        // TOCTOU: xem comment tại updateProfile
        try {
            return toAdminResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Username or email already taken");
        }
    }

    @Transactional(readOnly = true)
    public PagedResult<UserAdminResponse> listUsers(String q, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        var result = StringUtils.hasText(q)
                ? userRepository.searchUsers(q, pageable)
                : userRepository.findByDeletedAtIsNull(pageable);
        return PagedResult.of(result.map(this::toAdminResponse));
    }

    @Transactional
    public UserAdminResponse setUserStatus(Long userId, SetUserStatusRequest request) {
        User user = findForUpdate(userId);
        var before = auditState(user);
        user.setIsActive(request.isActive());
        user.setUpdatedAt(Instant.now());
        if (!request.isActive()) {
            // Khóa tài khoản phải thu hồi refresh token ngay — nếu không, user bị khóa
            // vẫn giữ được phiên vô thời hạn qua vòng lặp refresh (CRITICAL #2).
            refreshTokenService.revokeAll(userId);
        }
        var result = toAdminResponse(userRepository.save(user));
        adminAuditService.record("ADMIN_USER_STATUS_CHANGED", "USER", userId, null, request.reason(), before, auditState(user));
        return result;
    }

    @Transactional
    public void deleteUser(Long userId) {
        deleteUser(userId, null);
    }

    @Transactional
    public void deleteUser(Long userId, String reason) {
        User user = findForUpdate(userId);
        var before = auditState(user);
        if (user.getDeletedAt() != null) {
            throw new IllegalArgumentException("User already deleted");
        }

        // Ghi nhớ role active TRƯỚC khi soft-delete để biết cache phân quyền nào cần evict
        List<UserRole> ownerRoles = userRoleRepository.findActiveBusinessRolesForUser(userId);
        List<UserRole> storeRoles = userRoleRepository.findActiveStoreRolesWithBusinessDetails(userId);

        user.setDeletedAt(Instant.now());
        user.setIsActive(false);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        adminAuditService.record("ADMIN_USER_DELETED", "USER", userId, null, reason, before, auditState(user));

        // Soft-delete toàn bộ role — nếu không, role rác vẫn active trong DB và evaluator
        // (findActiveStoreRole/findActiveBusinessRole) vẫn cho user đã xóa pass phân quyền
        userRoleRepository.softDeleteAllByUserId(userId, Instant.now());

        // Xóa mềm cũng phải thu hồi toàn bộ refresh token (như khóa tài khoản):
        // chặn user đã offboard tiếp tục gia hạn phiên (CRITICAL #2).
        refreshTokenService.revokeAll(userId);

        // Evict cache phân quyền SAU commit — evict giữa transaction sẽ bị request khác
        // cache lại role cũ chưa commit (xem StoreService.evictMemberCachesAfterCommit)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (UserRole ur : ownerRoles) {
                    businessAccessEvaluator.evictBusinessRoleCache(userId, ur.getBusiness().getId());
                }
                for (UserRole ur : storeRoles) {
                    storeAccessEvaluator.evictStoreRoleCache(userId, ur.getStore().getId());
                    businessAccessEvaluator.evictBusinessMemberCache(userId, ur.getStore().getBusiness().getId());
                }
            }
        });
    }

    private void checkUsernameAndEmailUnique(String username, String email, Long excludeId) {
        userRepository.findByUsername(username)
                .filter(u -> !u.getId().equals(excludeId))
                .ifPresent(u -> { throw new BusinessRuleException(ErrorCode.USERNAME_TAKEN, "Username already taken"); });
        userRepository.findByEmail(email)
                .filter(u -> !u.getId().equals(excludeId))
                .ifPresent(u -> { throw new BusinessRuleException(ErrorCode.EMAIL_TAKEN, "Email already registered"); });
    }

    private User findOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User not found: " + userId));
    }

    private User findForUpdate(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User not found: " + userId));
    }

    private record UserAuditState(Long id, String username, Boolean isActive, Instant deletedAt) {}

    private UserAuditState auditState(User user) {
        return new UserAuditState(user.getId(), user.getUsername(), user.getIsActive(), user.getDeletedAt());
    }

    private UserSummaryResponse toResponse(User user) {
        return new UserSummaryResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getIsActive()
        );
    }

    private UserAdminResponse toAdminResponse(User user) {
        return new UserAdminResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getIsActive(),
                user.getCreatedAt(),
                user.getDeletedAt()
        );
    }
}
