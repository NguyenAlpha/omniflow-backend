package com.omniflow.backend.config;

import com.omniflow.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Cấu hình các bean Spring Security dùng chung cho toàn bộ ứng dụng.
 *
 * <h3>Vai trò của từng bean</h3>
 * <ul>
 *   <li>{@link #userDetailsService()} — load {@code User} từ DB; chỉ dùng trong quá trình
 *       <b>đăng nhập</b> (để {@link DaoAuthenticationProvider} xác minh password BCrypt).
 *       {@link com.omniflow.backend.security.JwtAuthFilter} <b>không</b> dùng bean này —
 *       thông tin user được lấy thẳng từ JWT claims, không có DB call.</li>
 *   <li>{@link #authenticationProvider()} — kết nối {@code UserDetailsService} với
 *       {@code BCryptPasswordEncoder} để tạo thành một provider hoàn chỉnh.</li>
 *   <li>{@link #authenticationManager(AuthenticationConfiguration)} — expose
 *       {@code AuthenticationManager} để {@link com.omniflow.backend.service.AuthService}
 *       có thể inject và gọi {@code authenticate()} khi đăng nhập.</li>
 *   <li>{@link #passwordEncoder()} — BCrypt dùng cho cả encode (đăng ký) và verify (đăng nhập).</li>
 * </ul>
 *
 * <h3>Thành phần liên quan</h3>
 * <ul>
 *   <li>{@link com.omniflow.backend.service.AuthService} — inject {@code AuthenticationManager}
 *       để xác thực username/password khi đăng nhập</li>
 *   <li>{@link SecurityConfig} — inject {@code AuthenticationProvider} vào filter chain</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class ApplicationConfig {

    private final UserRepository userRepository;

    /**
     * Load {@code User} từ DB theo username hoặc email.
     *
     * <p><b>Chỉ dùng trong luồng đăng nhập</b> — {@link DaoAuthenticationProvider} gọi method
     * này để lấy {@code UserDetails} rồi so khớp password bằng BCrypt. Sau khi xác thực thành công,
     * {@link com.omniflow.backend.service.AuthService} tự query DB lại để build {@code AuthResponse}.
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
     * Provider xác thực kết hợp DB lookup và BCrypt password verify.
     *
     * <p>Khi {@code AuthenticationManager.authenticate()} được gọi:
     * <ol>
     *   <li>Provider gọi {@code userDetailsService.loadUserByUsername()} để lấy {@code User}</li>
     *   <li>So khớp raw password với {@code passwordHash} bằng BCrypt</li>
     *   <li>Kiểm tra {@code user.isEnabled()} — false nếu user bị deactivate hoặc soft-deleted</li>
     *   <li>Trả về {@code UsernamePasswordAuthenticationToken} nếu thành công</li>
     * </ol>
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * Expose {@link AuthenticationManager} như một Spring bean.
     *
     * <p>Spring Boot không tự expose bean này dù nó được tạo nội bộ —
     * phải khai báo tường minh để {@link com.omniflow.backend.service.AuthService}
     * có thể {@code @Autowired} và gọi {@code authenticate()} trong luồng login.
     *
     * @param config cung cấp {@code AuthenticationManager} đã được Spring cấu hình
     *               với {@code AuthenticationProvider} đăng ký ở {@link SecurityConfig}
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
}
