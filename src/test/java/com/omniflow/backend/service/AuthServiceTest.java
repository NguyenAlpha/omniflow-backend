package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.auth.LoginRequest;
import com.omniflow.backend.dto.request.auth.RegisterRequest;
import com.omniflow.backend.dto.response.auth.AuthResponse;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.UserRepository;
import com.omniflow.backend.repository.UserRoleRepository;
import com.omniflow.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private StoreMemberRepository storeMemberRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private AuthenticationManager authenticationManager;

    @InjectMocks
    private AuthService authService;

    private User savedUser;

    @BeforeEach
    void setUp() {
        savedUser = User.builder()
                .id(1L)
                .username("testuser")
                .email("test@example.com")
                .passwordHash("encoded_password")
                .fullName("Test User")
                .phone("0901234567")
                .isActive(true)
                .build();
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void register_success() {
        RegisterRequest request = new RegisterRequest(
                "testuser", "test@example.com", "password123", "Test User", "0901234567"
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(storeMemberRepository.findByUserIdAndDeletedAtIsNullWithStore(any())).thenReturn(List.of());
        when(userRoleRepository.findActiveStoreRolesWithDetails(any())).thenReturn(List.of());
        when(userRoleRepository.findByUserIdAndStoreIsNullAndDeletedAtIsNull(any())).thenReturn(List.of());
        when(jwtService.generateToken(any(), any(Map.class))).thenReturn("mock.jwt.token");

        AuthResponse response = authService.register(request);

        assertThat(response.accessToken()).isEqualTo("mock.jwt.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.user().username()).isEqualTo("testuser");
        assertThat(response.user().email()).isEqualTo("test@example.com");
        assertThat(response.storeMemberships()).isEmpty();
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_throwsWhenUsernameTaken() {
        RegisterRequest request = new RegisterRequest(
                "testuser", "test@example.com", "password123", "Test User", null
        );

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(savedUser));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username already taken");

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_throwsWhenEmailTaken() {
        RegisterRequest request = new RegisterRequest(
                "newuser", "test@example.com", "password123", "New User", null
        );

        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(savedUser));

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email already registered");

        verify(userRepository, never()).save(any());
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_success() {
        LoginRequest request = new LoginRequest("testuser", "password123", null);

        when(userRepository.findByUsernameOrEmail("testuser", "testuser")).thenReturn(Optional.of(savedUser));
        when(storeMemberRepository.findByUserIdAndDeletedAtIsNullWithStore(1L)).thenReturn(List.of());
        when(userRoleRepository.findActiveStoreRolesWithDetails(1L)).thenReturn(List.of());
        when(userRoleRepository.findByUserIdAndStoreIsNullAndDeletedAtIsNull(1L)).thenReturn(List.of());
        when(jwtService.generateToken(any(), any(Map.class))).thenReturn("mock.jwt.token");

        AuthResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("mock.jwt.token");
        assertThat(response.user().username()).isEqualTo("testuser");
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void login_throwsWhenBadCredentials() {
        LoginRequest request = new LoginRequest("testuser", "wrongpassword", null);

        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        verify(userRepository, never()).findByUsernameOrEmail(anyString(), anyString());
    }
}
