package com.omniflow.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.backend.config.ApplicationConfig;
import com.omniflow.backend.config.SecurityConfig;
import com.omniflow.backend.dto.request.auth.LoginRequest;
import com.omniflow.backend.dto.request.auth.RegisterRequest;
import com.omniflow.backend.dto.response.auth.AuthResponse;
import com.omniflow.backend.dto.response.auth.UserSummaryResponse;
import com.omniflow.backend.repository.UserRepository;
import com.omniflow.backend.security.JwtService;
import com.omniflow.backend.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, ApplicationConfig.class})
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AuthService authService;
    @MockitoBean JwtService jwtService;
    @MockitoBean UserRepository userRepository;

    private AuthResponse mockAuthResponse;

    @BeforeEach
    void setUp() {
        UserSummaryResponse user = new UserSummaryResponse(1L, null, "testuser", "test@example.com", "Test User", null, true);
        mockAuthResponse = new AuthResponse("mock.jwt.token", "Bearer", 86400000L, user, List.of());
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void register_success() throws Exception {
        when(authService.register(any())).thenReturn(mockAuthResponse);

        RegisterRequest request = new RegisterRequest("testuser", "test@example.com", "password123", "Test User", null);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.user.username").value("testuser"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void register_returnsBadRequest_whenUsernameTaken() throws Exception {
        when(authService.register(any())).thenThrow(new IllegalArgumentException("Username already taken"));

        RegisterRequest request = new RegisterRequest("testuser", "test@example.com", "password123", "Test User", null);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Username already taken"));
    }

    @Test
    void register_returnsBadRequest_whenEmailInvalid() throws Exception {
        RegisterRequest request = new RegisterRequest("testuser", "not-an-email", "password123", "Test User", null);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.field").value("email"));
    }

    @Test
    void register_returnsBadRequest_whenPasswordTooShort() throws Exception {
        RegisterRequest request = new RegisterRequest("testuser", "test@example.com", "short", "Test User", null);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.field").value("password"));
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_success() throws Exception {
        when(authService.login(any())).thenReturn(mockAuthResponse);

        LoginRequest request = new LoginRequest("testuser", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.data.user.username").value("testuser"));
    }

    @Test
    void login_returnsUnauthorized_whenBadCredentials() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        LoginRequest request = new LoginRequest("testuser", "wrongpassword");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_returnsBadRequest_whenMissingFields() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    // ── Security ──────────────────────────────────────────────────────────────

    @Test
    void protectedEndpoint_returnsUnauthorized_withoutToken() throws Exception {
        mockMvc.perform(post("/api/products"))
                .andExpect(status().isUnauthorized());
    }
}
