package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.user.ChangePasswordRequest;
import com.quiktech.backend.dto.request.user.SetUserStatusRequest;
import com.quiktech.backend.dto.request.user.UpdateProfileRequest;
import com.quiktech.backend.dto.response.auth.UserSummaryResponse;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.user.UserAdminResponse;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.entity.UserRole;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.repository.UserRoleRepository;
import com.quiktech.backend.security.BusinessAccessEvaluator;
import com.quiktech.backend.security.StoreAccessEvaluator;
import com.quiktech.backend.security.UserPrincipal;
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

    @Transactional(readOnly = true)
    public UserSummaryResponse getProfile(UserPrincipal currentUser) {
        User user = findOrThrow(currentUser.userId());
        return toResponse(user);
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
            throw new IllegalArgumentException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        // Đổi mật khẩu = thu hồi toàn bộ refresh token: nếu kẻ tấn công đang giữ
        // refresh token cũ (lý do khiến user đổi mật khẩu) thì phiên đó bị cắt ngay,
        // không thể tự gia hạn tiếp. Access token cũ vẫn sống tối đa jwt.expiration.
        refreshTokenService.revokeAll(user.getId());
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
        User user = findOrThrow(userId);
        user.setIsActive(request.isActive());
        user.setUpdatedAt(Instant.now());
        if (!request.isActive()) {
            // Khóa tài khoản phải thu hồi refresh token ngay — nếu không, user bị khóa
            // vẫn giữ được phiên vô thời hạn qua vòng lặp refresh (CRITICAL #2).
            refreshTokenService.revokeAll(userId);
        }
        return toAdminResponse(userRepository.save(user));
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = findOrThrow(userId);
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
                .ifPresent(u -> { throw new IllegalArgumentException("Username already taken"); });
        userRepository.findByEmail(email)
                .filter(u -> !u.getId().equals(excludeId))
                .ifPresent(u -> { throw new IllegalArgumentException("Email already registered"); });
    }

    private User findOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User not found: " + userId));
    }

    private UserSummaryResponse toResponse(User user) {
        return new UserSummaryResponse(
                user.getId(),
                null,
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
