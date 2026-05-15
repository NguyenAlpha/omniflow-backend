package com.omniflow.backend.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Phát hành JWT sau khi đăng nhập hoặc đăng ký thành công.
 *
 * <p>Service này <b>chỉ ký token</b> — việc validate token mỗi request được Spring Security
 * xử lý tự động qua {@code BearerTokenAuthenticationFilter} và {@code NimbusJwtDecoder},
 * không cần code thủ công ở đây.
 *
 * <p><b>Thành phần liên quan:</b>
 * <ul>
 *   <li>{@link com.omniflow.backend.service.AuthService} — gọi {@code generateToken()} sau khi
 *       xác thực thành công để trả token về client</li>
 *   <li>{@link com.omniflow.backend.config.ApplicationConfig#jwtEncoder(String)} — cung cấp
 *       {@code JwtEncoder} bean dùng HMAC-SHA256</li>
 *   <li>{@link UserPrincipalConverter} — phía nhận: convert JWT đã validate thành
 *       {@code UserPrincipal} lưu vào SecurityContext</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    // Header tường minh bắt buộc — nếu dùng JwtEncoderParameters.from(claims) không có header,
    // NimbusJwtEncoder mặc định RS256 và ném lỗi "Failed to select a JWK signing key"
    private static final JwsHeader HS256_HEADER = JwsHeader.with(MacAlgorithm.HS256).build();

    private final JwtEncoder jwtEncoder;

    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * Ký và trả về JWT HS256 chứa các claims được truyền vào.
     *
     * <p>Caller có trách nhiệm đưa {@code userId} và {@code roles} vào {@code extraClaims} —
     * hai claims này được {@link UserPrincipalConverter} đọc lại để build {@code UserPrincipal}.
     *
     * @param userDetails  dùng để lấy {@code username} làm {@code subject} của token
     * @param extraClaims  các claims bổ sung (thường là {@code userId}, {@code roles})
     * @return chuỗi JWT đã ký, sẵn sàng trả về client dưới dạng Bearer token
     */
    public String generateToken(UserDetails userDetails, Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .subject(userDetails.getUsername())
                .issuedAt(now)
                .expiresAt(now.plusMillis(expiration));
        extraClaims.forEach(builder::claim);
        return jwtEncoder.encode(JwtEncoderParameters.from(HS256_HEADER, builder.build())).getTokenValue();
    }
}
