package com.omniflow.backend.security;

import com.omniflow.backend.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;
    private User user;

    // 64 hex chars = 32 bytes = valid HS256 key
    private static final String SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final long EXPIRATION = 86400000L;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expiration", EXPIRATION);

        user = User.builder()
                .id(1L)
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hashed")
                .fullName("Test User")
                .isActive(true)
                .build();
    }

    @Test
    void generateToken_returnsNonNullToken() {
        String token = jwtService.generateToken(user, Map.of());
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void extractUsername_returnsCorrectUsername() {
        String token = jwtService.generateToken(user, Map.of());
        assertThat(jwtService.extractUsername(token)).isEqualTo("testuser");
    }

    @Test
    void isTokenValid_returnsTrueForValidToken() {
        String token = jwtService.generateToken(user, Map.of());
        assertThat(jwtService.isTokenValid(token, user)).isTrue();
    }

    @Test
    void isTokenValid_returnsFalseForDifferentUser() {
        String token = jwtService.generateToken(user, Map.of());

        User otherUser = User.builder()
                .username("otheruser")
                .email("other@example.com")
                .passwordHash("hashed")
                .fullName("Other User")
                .isActive(true)
                .build();

        assertThat(jwtService.isTokenValid(token, otherUser)).isFalse();
    }

    @Test
    void isTokenValid_returnsFalseForExpiredToken() {
        ReflectionTestUtils.setField(jwtService, "expiration", -1000L);
        String token = jwtService.generateToken(user, Map.of());
        assertThat(jwtService.isTokenValid(token, user)).isFalse();
    }

    @Test
    void generateToken_includesExtraClaims() {
        String token = jwtService.generateToken(user, Map.of("userId", 1L));
        Long userId = jwtService.extractClaim(token, claims -> claims.get("userId", Long.class));
        assertThat(userId).isEqualTo(1L);
    }
}
