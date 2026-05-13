package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.user.ChangePasswordRequest;
import com.omniflow.backend.dto.request.user.SetUserStatusRequest;
import com.omniflow.backend.dto.request.user.UpdateProfileRequest;
import com.omniflow.backend.dto.response.auth.UserSummaryResponse;
import com.omniflow.backend.dto.response.common.PagedResult;
import com.omniflow.backend.dto.response.user.UserAdminResponse;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.repository.UserRepository;
import com.omniflow.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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
        return toResponse(userRepository.save(user));
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
        return toAdminResponse(userRepository.save(user));
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
        return toAdminResponse(userRepository.save(user));
    }

    @Transactional
    public void deleteUser(Long userId) {
        User user = findOrThrow(userId);
        if (user.getDeletedAt() != null) {
            throw new IllegalArgumentException("User already deleted");
        }
        user.setDeletedAt(Instant.now());
        user.setIsActive(false);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
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
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
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
