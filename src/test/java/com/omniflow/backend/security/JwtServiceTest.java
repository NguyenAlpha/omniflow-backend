package com.omniflow.backend.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.omniflow.backend.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtDecoder jwtDecoder;
    private User user;

    private static final String SECRET_B64 = "dGVzdFNlY3JldEtleUZvckp3dFRlc3RpbmdPbmx5MTI=";
    private static final long EXPIRATION = 86400000L;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = Base64.getDecoder().decode(SECRET_B64);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");

        OctetSequenceKey jwk = new OctetSequenceKey.Builder(keyBytes).algorithm(JWSAlgorithm.HS256).build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        jwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();

        jwtService = new JwtService(encoder);
        ReflectionTestUtils.setField(jwtService, "expiration", EXPIRATION);

        user = User.builder()
                .id(1L).username("testuser").email("test@example.com")
                .passwordHash("hashed").fullName("Test User").isActive(true).build();
    }

    @Test
    void generateToken_returnsNonNullToken() {
        String token = jwtService.generateToken(user, Map.of());
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void generateToken_subjectIsUsername() {
        String token = jwtService.generateToken(user, Map.of());
        assertThat(jwtDecoder.decode(token).getSubject()).isEqualTo("testuser");
    }

    @Test
    void generateToken_includesExtraClaims() {
        String token = jwtService.generateToken(user, Map.of("userId", 1L, "roles", List.of("ROLE_SUPER_ADMIN")));
        var jwt = jwtDecoder.decode(token);
        assertThat(jwt.<Number>getClaim("userId").longValue()).isEqualTo(1L);
        assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    void generateToken_hasExpirationSet() {
        String token = jwtService.generateToken(user, Map.of());
        var jwt = jwtDecoder.decode(token);
        assertThat(jwt.getExpiresAt()).isNotNull();
    }
}
