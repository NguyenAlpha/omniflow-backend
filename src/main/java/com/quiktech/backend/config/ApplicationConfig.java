package com.quiktech.backend.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.security.UserPrincipalConverter;
import com.quiktech.backend.security.JwtService;
import com.quiktech.backend.security.UserPrincipal;
import com.quiktech.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Cấu hình các bean Spring Security dùng chung cho toàn bộ ứng dụng.
 *
 * <h3>Vai trò của từng bean</h3>
 * <ul>
 *   <li>{@link #userDetailsService()} — load {@code User} từ DB; chỉ dùng trong quá trình
 *       <b>đăng nhập</b> (để {@code DaoAuthenticationProvider} xác minh password BCrypt).
 *       {@code BearerTokenAuthenticationFilter} <b>không</b> dùng bean này —
 *       thông tin user được lấy thẳng từ JWT claims, không có DB call.</li>
 *   <li>{@link #authenticationManager(AuthenticationConfiguration)} — expose
 *       {@code AuthenticationManager} để {@link AuthService}
 *       có thể inject và gọi {@code authenticate()} khi đăng nhập.</li>
 *   <li>{@link #passwordEncoder()} — BCrypt dùng cho cả encode (đăng ký) và verify (đăng nhập).</li>
 *   <li>{@link #jwtDecoder(String)} — NimbusJwtDecoder dùng HMAC-SHA256; được
 *       {@code BearerTokenAuthenticationFilter} dùng để validate token mỗi request.</li>
 *   <li>{@link #jwtEncoder(String)} — NimbusJwtEncoder dùng để ký token trong
 *       {@link JwtService} sau khi đăng nhập thành công.</li>
 *   <li>{@link #jwtAuthConverter()} — convert {@code Jwt} đã validate thành
 *       {@code UsernamePasswordAuthenticationToken} với {@code UserPrincipal}, lưu vào SecurityContext.</li>
 * </ul>
 *
 * <h3>Thành phần liên quan</h3>
 * <ul>
 *   <li>{@link AuthService} — inject {@code AuthenticationManager}
 *       để xác thực username/password khi đăng nhập</li>
 *   <li>{@link SecurityConfig} — inject {@code jwtAuthConverter} vào filter chain</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class ApplicationConfig {

    private final UserRepository userRepository;

    /**
     * Load {@code User} từ DB theo username hoặc email.
     *
     * <p><b>Chỉ dùng trong luồng đăng nhập</b> — {@code DaoAuthenticationProvider} gọi method
     * này để lấy {@code UserDetails} rồi so khớp password bằng BCrypt. Sau khi xác thực thành công,
     * {@link AuthService} tự query DB lại để build {@code AuthResponse}.
     *
     * <p>Không load store-scoped roles ở đây vì chúng phụ thuộc context store và
     * không cần thiết cho BCrypt verify. Global roles cũng không load vì chúng
     * sẽ được nhúng vào JWT tại bước {@code buildAuthResponse}.
     *
     * <p>{@code findByUsernameOrEmail} cho phép đăng nhập bằng cả username lẫn email —
     * truyền cùng giá trị cho cả hai param để query OR.
     *
     * @throws UsernameNotFoundException nếu không tìm thấy user — Spring Security tự
     *                                   convert thành {@code BadCredentialsException} để
     *                                   không lộ thông tin "user không tồn tại"
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository.findByUsernameOrEmail(username, username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    /**
     * Expose {@link AuthenticationManager} như một Spring bean.
     *
     * <p>Spring Boot không tự expose bean này dù nó được tạo nội bộ —
     * phải khai báo tường minh để {@link AuthService}
     * có thể {@code @Autowired} và gọi {@code authenticate()} trong luồng login.
     *
     * @param config cung cấp {@code AuthenticationManager} đã được Spring tự cấu hình
     *               từ {@code UserDetailsService} và {@code PasswordEncoder} bean trong context
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * BCryptPasswordEncoder với strength mặc định (10 rounds).
     *
     * <p>10 rounds là điểm cân bằng giữa bảo mật và performance cho production —
     * mỗi lần hash tốn ~100ms trên phần cứng thông thường, đủ chậm để brute-force
     * không hiệu quả mà không ảnh hưởng đáng kể đến UX login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * NimbusJwtDecoder dùng HMAC-SHA256 để validate chữ ký của Bearer token.
     *
     * <p>Bean này được {@code BearerTokenAuthenticationFilter} tự động dùng mỗi request —
     * không cần gọi thủ công. Nếu token hết hạn hoặc chữ ký sai, filter trả về 401.
     *
     * <p>{@code jwt.secret} phải là chuỗi Base64 của key HMAC-SHA256 (ít nhất 32 bytes sau decode).
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${jwt.secret}") String secret) {
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        SecretKeySpec key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * NimbusJwtEncoder dùng HMAC-SHA256 để ký token khi đăng nhập / đăng ký thành công.
     *
     * <p>Dùng {@link OctetSequenceKey} với {@code .algorithm(JWSAlgorithm.HS256)} bắt buộc —
     * nếu thiếu, Nimbus không chọn được key khi ký và ném {@code JOSEException}.
     * Key được wrap trong {@link ImmutableJWKSet} để tương thích với Nimbus JWK API.
     */
    @Bean
    public JwtEncoder jwtEncoder(@Value("${jwt.secret}") String secret) {
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(keyBytes).algorithm(JWSAlgorithm.HS256).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    /**
     * Converter chuyển đổi {@link Jwt} đã được validate thành {@code Authentication}.
     *
     * <p>Sau khi {@code BearerTokenAuthenticationFilter} xác minh chữ ký JWT, Spring Security
     * gọi converter này để tạo {@link UserPrincipal} từ claims
     * và lưu vào {@code SecurityContext}. Nhờ đó {@code @AuthenticationPrincipal UserPrincipal}
     * và {@code @PreAuthorize("@storeAccess....")} hoạt động đúng trong toàn bộ ứng dụng.
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter() {
        return new UserPrincipalConverter();
    }
}
