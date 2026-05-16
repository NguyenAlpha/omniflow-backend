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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.findByUsername(request.username()).isPresent()) {
            throw new IllegalArgumentException("Username already taken");
        }
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .phone(request.phone())
                .build();

        return buildAuthResponse(userRepository.save(user));
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password())
        );

        User user = userRepository.findByUsernameOrEmail(request.usernameOrEmail(), request.usernameOrEmail())
                .orElseThrow();

        return buildAuthResponse(user);
    }

    /**
     * Tạo AuthResponse đầy đủ: JWT (chứa userId + global roles) + user summary + store memberships.
     * JWT nhúng global roles để JwtAuthFilter không cần gọi DB khi xác thực.
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

        // Global roles nhúng vào JWT — JwtAuthFilter sẽ extract, không cần DB
        List<String> globalRoles = userRoleRepository
                .findByUserIdAndStoreIsNullAndDeletedAtIsNull(user.getId())
                .stream()
                .filter(ur -> Boolean.TRUE.equals(ur.getIsActive()))
                .map(ur -> ur.getRole().getName().name())
                .toList();

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

        return new AuthResponse(token, "Bearer", 86400000L, userSummary, memberships);
    }
}
