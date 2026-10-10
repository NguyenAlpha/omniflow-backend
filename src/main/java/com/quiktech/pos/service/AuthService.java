package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.auth.LoginRequest;
import com.quiktech.pos.dto.request.auth.RegisterRequest;
import com.quiktech.pos.dto.response.auth.AuthResponse;
import com.quiktech.pos.dto.response.auth.BusinessMembershipResponse;
import com.quiktech.pos.dto.response.auth.StoreInfo;
import com.quiktech.pos.dto.response.auth.UserSummaryResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.entity.StoreMember;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.exception.InvalidTokenException;
import com.quiktech.pos.repository.StoreMemberRepository;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import com.quiktech.pos.security.JwtService;
import com.quiktech.pos.security.LoginAttemptLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final StoreRepository storeRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptLimiter loginAttemptLimiter;

    @Value("${jwt.expiration}")
    private long jwtExpiration;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .phone(request.phone())
                .build();

        AuthResponse response;
        try {
            response = buildAuthResponse(userRepository.save(user));
        } catch (DataIntegrityViolationException e) {
            log.warn("Register failed: duplicate username or email: {}", request.username());
            throw new IllegalArgumentException("Username or email already taken");
        }
        log.info("User registered: username={}, email={}", user.getUsername(), user.getEmail());
        return response;
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Tra user trước để giới hạn đăng nhập sai theo tài khoản (username/email chung bucket)
        Optional<User> found = userRepository.findByUsernameOrEmail(request.usernameOrEmail(), request.usernameOrEmail());
        String accountKey = LoginAttemptLimiter.accountKey(found.map(User::getId).orElse(null), request.usernameOrEmail());
        loginAttemptLimiter.assertAllowed(accountKey);

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password())
            );
        } catch (BadCredentialsException wrongPassword) {
            // Chỉ sai mật khẩu mới bị tính; tài khoản bị khóa (DisabledException) không trừ lượt
            loginAttemptLimiter.recordFailure(accountKey);
            throw wrongPassword;
        }

        // authenticate() ở trên đã thành công nên user chắc chắn tồn tại — nếu vẫn
        // không tìm thấy (race hiếm: user bị xóa giữa 2 câu query) thì fail với message
        // rõ ràng thay vì NoSuchElementException không có context
        User user = found
                .orElseThrow(() -> new IllegalStateException(
                        "User not found after successful authentication: " + request.usernameOrEmail()));

        log.info("User logged in: username={}", user.getUsername());
        return buildAuthResponse(user);
    }

    /**
     * noRollbackFor bắt buộc (xem Javadoc RefreshTokenService.rotate): transaction này
     * bao cả rotate() (propagation REQUIRED) — nếu InvalidTokenException gây rollback
     * ở tầng này thì các UPDATE revoke trong rotate()/nhánh user-disabled đều bị hủy.
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotateResult result = refreshTokenService.rotate(refreshToken);
        // User bị xóa mềm sẽ không tìm thấy do @SQLRestriction("deleted_at IS NULL");
        // user bị khóa thì isEnabled() = false. Cả 2 trường hợp: thu hồi toàn bộ refresh
        // token để chặn user offboarded tự gia hạn phiên vô thời hạn (CRITICAL #2).
        User user = userRepository.findById(result.userId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            refreshTokenService.revokeAll(result.userId());
            log.warn("Refresh blocked for disabled/deleted userId={} — all tokens revoked", result.userId());
            throw new InvalidTokenException(ErrorCode.REFRESH_TOKEN_INVALID, "User account is disabled");
        }
        return buildBundle(user, result.newToken());
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenService.revokeAll(userId);
        log.info("User logged out: userId={}", userId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AuthResponse buildAuthResponse(User user) {
        String rtValue = refreshTokenService.create(user.getId());
        return buildBundle(user, rtValue);
    }

    /**
     * Tạo AuthResponse: JWT (userId + global roles) + user summary + business memberships.
     * OWNER → tất cả stores của business; MANAGER/STAFF → stores được assign, grouped by business.
     */
    private AuthResponse buildBundle(User user, String rtValue) {
        List<BusinessMembershipResponse> memberships = new ArrayList<>();

        // Business OWNER entries — role + business eagerly fetched
        List<UserRole> businessRoles = userRoleRepository.findActiveBusinessRolesForUser(user.getId());
        for (UserRole ur : businessRoles) {
            Long businessId = ur.getBusiness().getId();
            List<StoreInfo> storeInfos = storeRepository.findByBusinessIdAndDeletedAtIsNull(businessId)
                    .stream()
                    .map(s -> new StoreInfo(s.getId(), s.getName(), ur.getRole().getName(), null))
                    .toList();
            memberships.add(new BusinessMembershipResponse(businessId, ur.getBusiness().getName(), storeInfos));
        }

        // MANAGER / STAFF entries — role + store + store.business eagerly fetched
        List<UserRole> storeRoles = userRoleRepository.findActiveStoreRolesWithBusinessDetails(user.getId());
        if (!storeRoles.isEmpty()) {
            Map<Long, StoreMember> memberByStoreId = storeMemberRepository
                    .findByUserIdAndDeletedAtIsNullWithStore(user.getId())
                    .stream()
                    .collect(Collectors.toMap(m -> m.getStore().getId(), m -> m));

            storeRoles.stream()
                    .collect(Collectors.groupingBy(
                            ur -> ur.getStore().getBusiness().getId(),
                            LinkedHashMap::new,
                            Collectors.toList()))
                    .forEach((businessId, roles) -> {
                        String businessName = roles.get(0).getStore().getBusiness().getName();
                        List<StoreInfo> storeInfos = roles.stream()
                                .map(ur -> {
                                    StoreMember m = memberByStoreId.get(ur.getStore().getId());
                                    return new StoreInfo(
                                            ur.getStore().getId(),
                                            ur.getStore().getName(),
                                            ur.getRole().getName(),
                                            m != null ? m.getPositionTitle() : null
                                    );
                                })
                                .toList();
                        memberships.add(new BusinessMembershipResponse(businessId, businessName, storeInfos));
                    });
        }

        // Global roles nhúng vào JWT — UserPrincipalConverter extract, không cần DB call
        List<String> globalRoles = userRoleRepository
                .findByUserIdAndBusinessIsNullAndStoreIsNullAndDeletedAtIsNull(user.getId())
                .stream()
                .filter(ur -> Boolean.TRUE.equals(ur.getIsActive()))
                .map(ur -> ur.getRole().getName().name())
                .toList();

        log.info("Building token for userId={}: globalRoles={}, businessCount={}",
                user.getId(), globalRoles, memberships.size());

        String token = jwtService.generateToken(user, Map.of(
                "userId", user.getId(),
                "roles", globalRoles
        ));

        UserSummaryResponse userSummary = new UserSummaryResponse(
                user.getId(), null, user.getUsername(), user.getEmail(),
                user.getFullName(), user.getPhone(), user.getIsActive()
        );

        return new AuthResponse(token, "Bearer", jwtExpiration / 1000, userSummary, memberships, rtValue);
    }
}
