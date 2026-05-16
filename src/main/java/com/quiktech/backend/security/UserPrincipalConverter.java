package com.quiktech.backend.security;

import com.quiktech.backend.config.ApplicationConfig;
import com.quiktech.backend.config.SecurityConfig;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * Chuyển đổi {@link Jwt} đã được validate thành {@link AbstractAuthenticationToken}
 * có principal là {@link UserPrincipal}.
 *
 * <p>Được đăng ký làm {@code jwtAuthenticationConverter} trong {@link SecurityConfig},
 * nên sau khi {@code BearerTokenAuthenticationFilter} xác minh chữ ký JWT, Spring Security tự động
 * gọi converter này để build {@code Authentication} và lưu vào {@code SecurityContext}.
 *
 * <p><b>Claims đọc từ JWT:</b>
 * <ul>
 *   <li>{@code sub} — username, lấy qua {@link Jwt#getSubject()}</li>
 *   <li>{@code userId} — ID nội bộ của user; kiểu có thể là {@code Integer} hoặc {@code Long}
 *       tùy JSON deserializer, nên cần normalize về {@code Long}</li>
 *   <li>{@code roles} — danh sách role string, map thành {@link SimpleGrantedAuthority}
 *       để Spring Security nhận diện cho {@code @PreAuthorize}</li>
 * </ul>
 *
 * <p><b>Thành phần liên quan:</b>
 * <ul>
 *   <li>{@link JwtService#generateToken} — nơi nhúng {@code userId} và {@code roles} vào token</li>
 *   <li>{@link ApplicationConfig#jwtAuthConverter()} — đăng ký bean này</li>
 *   <li>{@link StoreAccessEvaluator} — đọc {@code UserPrincipal}
 *       từ SecurityContext để kiểm tra quyền store-scoped</li>
 * </ul>
 */
public class UserPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String username = jwt.getSubject();
        Long userId = extractUserId(jwt);
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) roles = List.of();

        UserPrincipal principal = new UserPrincipal(userId, username, roles);

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        return new UsernamePasswordAuthenticationToken(principal, jwt, authorities);
    }

    /**
     * Normalize claim {@code userId} về {@code Long}.
     *
     * <p>Jackson deserialize số nhỏ thành {@code Integer}, số lớn thành {@code Long} —
     * pattern matching đảm bảo cả hai trường hợp đều được xử lý đúng.
     */
    private Long extractUserId(Jwt jwt) {
        Object val = jwt.getClaim("userId");
        if (val instanceof Long l)    return l;
        if (val instanceof Integer i) return i.longValue();
        if (val instanceof Number n)  return n.longValue();
        return null;
    }
}
