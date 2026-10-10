package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.auth.LoginRequest;
import com.quiktech.pos.dto.request.auth.RegisterRequest;
import com.quiktech.pos.dto.response.auth.AuthResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.exception.BusinessRuleException;
import com.quiktech.pos.exception.InvalidTokenException;
import com.quiktech.pos.exception.RateLimitExceededException;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.security.LoginAttemptLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private LoginAttemptLimiter loginAttemptLimiter;
    @Mock private AuthResponseAssembler authResponseAssembler;

    @InjectMocks private AuthService authService;

    private static final String USER_KEY = "user:7";

    private final AuthResponse assembled = mock(AuthResponse.class);

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    void register_hashesPassword_andReturnsResponseWithNewRefreshToken() {
        User saved = user(7L, true, null);
        when(passwordEncoder.encode("secret1")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(refreshTokenService.create(7L)).thenReturn("rt-new");
        when(authResponseAssembler.assemble(saved, "rt-new")).thenReturn(assembled);

        AuthResponse response = authService.register(registerRequest());

        assertThat(response).isSameAs(assembled);
        verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(u ->
                "bcrypt-hash".equals(u.getPasswordHash()) && "an".equals(u.getUsername())));
    }

    @Test
    void register_duplicateUsername_becomesUsernameTaken() {
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenThrow(uniqueViolation("uq_users_username_active"));

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USERNAME_TAKEN);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void register_duplicateEmail_becomesEmailTaken() {
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenThrow(uniqueViolation("uq_users_email_active"));

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_TAKEN);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void register_unknownConstraintViolation_staysValidationError() {
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenThrow(uniqueViolation("some_other_constraint"));

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username or email already taken");
    }

    // Giống lỗi thật: Spring bọc exception của driver, message PostgreSQL chứa tên index
    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    @Test
    void register_constraintErrorAfterSave_isNotReportedAsDuplicateUser() {
        when(passwordEncoder.encode(anyString())).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenReturn(user(7L, true, null));
        when(refreshTokenService.create(7L)).thenThrow(new DataIntegrityViolationException("refresh_tokens"));

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_success_createsRefreshTokenAndAssemblesResponse() {
        User found = user(7L, true, null);
        when(userRepository.findByUsernameOrEmail("an", "an")).thenReturn(Optional.of(found));
        when(refreshTokenService.create(7L)).thenReturn("rt-new");
        when(authResponseAssembler.assemble(found, "rt-new")).thenReturn(assembled);

        assertThat(authService.login(new LoginRequest("an", "secret1"))).isSameAs(assembled);
        verify(loginAttemptLimiter).assertAllowed(USER_KEY);
        verify(loginAttemptLimiter, never()).recordFailure(anyString());
    }

    @Test
    void login_wrongPassword_consumesAttempt_andReturns401() {
        when(userRepository.findByUsernameOrEmail("an", "an")).thenReturn(Optional.of(user(7L, true, null)));
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("an", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        verify(loginAttemptLimiter).recordFailure(USER_KEY);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void login_unknownAccount_isThrottledByHashedNameKey() {
        when(userRepository.findByUsernameOrEmail("ghost", "ghost")).thenReturn(Optional.empty());
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "x")))
                .isInstanceOf(BadCredentialsException.class);
        String nameKey = LoginAttemptLimiter.accountKey(null, "ghost");
        assertThat(nameKey).startsWith("name:");
        verify(loginAttemptLimiter).recordFailure(nameKey);
    }

    @Test
    void login_disabledAccount_doesNotConsumeAttempt() {
        when(userRepository.findByUsernameOrEmail("an", "an")).thenReturn(Optional.of(user(7L, false, null)));
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("an", "secret1")))
                .isInstanceOf(DisabledException.class);
        verify(loginAttemptLimiter, never()).recordFailure(anyString());
    }

    @Test
    void login_noAttemptsLeft_rejectsBeforeCheckingPassword() {
        when(userRepository.findByUsernameOrEmail("an", "an")).thenReturn(Optional.of(user(7L, true, null)));
        doThrow(new RateLimitExceededException("Too many failed login attempts. Please try again later.", 899, 5, 0))
                .when(loginAttemptLimiter).assertAllowed(USER_KEY);

        assertThatThrownBy(() -> authService.login(new LoginRequest("an", "secret1")))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(authenticationManager, refreshTokenService);
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    void refresh_success_assemblesResponseWithRotatedToken() {
        User active = user(7L, true, null);
        when(refreshTokenService.rotate("rt-old")).thenReturn(new RefreshTokenService.RotateResult("rt-rotated", 7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(active));
        when(authResponseAssembler.assemble(active, "rt-rotated")).thenReturn(assembled);

        assertThat(authService.refresh("rt-old")).isSameAs(assembled);
        verify(refreshTokenService, never()).revokeAll(any());
    }

    @Test
    void refresh_lockedAccount_revokesAllTokens() {
        when(refreshTokenService.rotate("rt-old")).thenReturn(new RefreshTokenService.RotateResult("rt-rotated", 7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, false, null)));

        assertThatThrownBy(() -> authService.refresh("rt-old"))
                .isInstanceOf(InvalidTokenException.class)
                .extracting(e -> ((InvalidTokenException) e).getErrorCode())
                .isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID);
        verify(refreshTokenService).revokeAll(7L);
        verifyNoInteractions(authResponseAssembler);
    }

    @Test
    void refresh_deletedAccount_revokesAllTokens() {
        when(refreshTokenService.rotate("rt-old")).thenReturn(new RefreshTokenService.RotateResult("rt-rotated", 7L));
        // Soft-deleted users are hidden by @SQLRestriction → findById returns empty
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("rt-old")).isInstanceOf(InvalidTokenException.class);
        verify(refreshTokenService).revokeAll(7L);
    }

    @Test
    void refresh_reusedToken_propagatesRotateError() {
        when(refreshTokenService.rotate("rt-used"))
                .thenThrow(new InvalidTokenException(ErrorCode.REFRESH_TOKEN_INVALID, "Refresh token already used"));

        assertThatThrownBy(() -> authService.refresh("rt-used"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessage("Refresh token already used");
        verifyNoInteractions(userRepository, authResponseAssembler);
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_revokesAllRefreshTokens() {
        authService.logout(7L);

        verify(refreshTokenService).revokeAll(7L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RegisterRequest registerRequest() {
        return new RegisterRequest("an", "an@test.com", "secret1", "Nguyễn An", null);
    }

    private static User user(Long id, boolean active, Instant deletedAt) {
        return User.builder().id(id).username("an").email("an@test.com")
                .passwordHash("bcrypt-hash").fullName("Nguyễn An").isActive(active).deletedAt(deletedAt).build();
    }
}
