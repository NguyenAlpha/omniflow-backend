package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.auth.LoginRequest;
import com.omniflow.backend.dto.request.auth.RegisterRequest;
import com.omniflow.backend.dto.response.auth.AuthResponse;
import com.omniflow.backend.dto.response.auth.UserSummaryResponse;
import com.omniflow.backend.dto.response.store.StoreMemberResponse;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.UserRepository;
import com.omniflow.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final StoreMemberRepository storeMemberRepository;
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

        userRepository.save(user);
        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password())
        );

        User user = userRepository.findByUsernameOrEmail(request.usernameOrEmail(), request.usernameOrEmail())
                .orElseThrow();

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        List<StoreMemberResponse> memberships = storeMemberRepository
                .findByUserIdAndDeletedAtIsNull(user.getId())
                .stream()
                .map(m -> new StoreMemberResponse(
                        m.getId(),
                        m.getPublicId(),
                        user.getId(),
                        user.getUsername(),
                        m.getStore().getId(),
                        m.getRole(),
                        m.getPositionTitle(),
                        m.getJoinedDate(),
                        m.getIsActive(),
                        m.getSyncVersion(),
                        m.getLastModifiedAt()
                ))
                .toList();

        String token = jwtService.generateToken(user, Map.of("userId", user.getId()));

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
