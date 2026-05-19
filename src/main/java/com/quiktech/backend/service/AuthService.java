package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.auth.LoginRequest;
import com.quiktech.backend.dto.request.auth.RegisterRequest;
import com.quiktech.backend.dto.response.auth.AuthResponse;
import com.quiktech.backend.dto.response.auth.UserSummaryResponse;
import com.quiktech.backend.dto.response.store.StoreMemberResponse;
import com.quiktech.backend.entity.StoreMember;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.repository.StoreMemberRepository;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.repository.UserRoleRepository;
import com.quiktech.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

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

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password())
        );

        User user = userRepository.findByUsernameOrEmail(request.usernameOrEmail(), request.usernameOrEmail())
                .orElseThrow();

        log.info("User logged in: username={}", user.getUsername());
        return buildAuthResponse(user);
    }

    /**
     * Tạo AuthResponse đầy đủ: JWT (chứa userId + global roles) + user summary + store memberships.
     * JWT nhúng global roles để UserPrincipalConverter extract trực tiếp, không cần DB call.
     */
    private AuthResponse buildAuthResponse(User user) {
        // StoreMember kèm Store — tránh lazy load
        Map<Long, StoreMember> membershipByStoreId = storeMemberRepository
                .findByUserIdAndDeletedAtIsNullWithStore(user.getId())
                .stream()
                .collect(Collectors.toMap(m -> m.getStore().getId(), m -> m));

        // Store-scoped roles với Role + Store JOIN FETCH
        List<StoreMemberResponse> memberships = userRoleRepository
                .findActiveStoreRolesWithDetails(user.getId())
                .stream()
                .map(ur -> {
                    StoreMember m = membershipByStoreId.get(ur.getStore().getId());
                    return new StoreMemberResponse(
                            m != null ? m.getId() : null,
                            m != null ? m.getPublicId() : null,
                            user.getId(),
                            user.getUsername(),
                            ur.getStore().getId(),
                            ur.getRole().getName(),
                            m != null ? m.getPositionTitle() : null,
                            m != null ? m.getJoinedDate() : null,
                            ur.getIsActive(),
                            m != null ? m.getSyncVersion() : null,
                            m != null ? m.getLastModifiedAt() : null
                    );
                })
                .toList();

        // Global roles nhúng vào JWT — UserPrincipalConverter sẽ extract, không cần DB
        List<String> globalRoles = userRoleRepository
                .findByUserIdAndStoreIsNullAndDeletedAtIsNull(user.getId())
                .stream()
                .filter(ur -> Boolean.TRUE.equals(ur.getIsActive()))
                .map(ur -> ur.getRole().getName().name())
                .toList();

        log.info("Building token for userId={}: globalRoles={}, storeCount={}", user.getId(), globalRoles, memberships.size());

        String token = jwtService.generateToken(user, Map.of(
                "userId", user.getId(),
                "roles", globalRoles
        ));

        UserSummaryResponse userSummary = new UserSummaryResponse(
                user.getId(),
                null,
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getIsActive()
        );

        return new AuthResponse(token, "Bearer", jwtExpiration, userSummary, memberships);
    }
}
