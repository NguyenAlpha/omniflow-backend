package com.omniflow.backend.config;

import com.omniflow.backend.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Cấu hình HTTP Security filter chain cho toàn bộ ứng dụng.
 *
 * <p><b>Chức năng chính:</b>
 * <ul>
 *   <li>Xác định endpoint nào public, endpoint nào yêu cầu xác thực</li>
 *   <li>Cấu hình stateless session (JWT — không dùng HttpSession)</li>
 *   <li>Đăng ký {@link JwtAuthFilter} vào filter chain</li>
 *   <li>Bật method-level security cho {@code @PreAuthorize}</li>
 * </ul>
 *
 * <p><b>Thành phần liên quan:</b>
 * <ul>
 *   <li>{@link JwtAuthFilter} — filter xác thực JWT, chạy trước mỗi request</li>
 *   <li>{@link ApplicationConfig} — cung cấp {@code AuthenticationProvider} và các bean auth khác</li>
 *   <li>{@link com.omniflow.backend.security.StoreAccessEvaluator} — được kích hoạt bởi
 *       {@code @EnableMethodSecurity} thông qua {@code @PreAuthorize("@storeAccess....")}</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
// Bật @PreAuthorize, @PostAuthorize trên method — cần thiết cho StoreAccessEvaluator
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    /**
     * Định nghĩa filter chain chính xử lý mọi HTTP request.
     * Thứ tự các bước cấu hình phản ánh thứ tự xử lý thực tế của Spring Security.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF không cần thiết với stateless JWT — không có cookie session để exploit
                .csrf(AbstractHttpConfigurer::disable)

                // config quyền truy cập API
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints công khai — đăng ký / đăng nhập không cần token
                        .requestMatchers("/api/auth/**").permitAll()
                        // Mọi request còn lại bắt buộc phải có JWT hợp lệ
                        .anyRequest().authenticated()
                )

                // Không tạo hay lưu HttpSession — mỗi request tự xác thực qua JWT
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Trả về 401 JSON-friendly thay vì redirect đến trang login mặc định của Spring
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                )

                // Dùng DaoAuthenticationProvider (username + Bcrypt) từ ApplicationConfig
                .authenticationProvider(authenticationProvider)

                // JwtAuthFilter chạy trước UsernamePasswordAuthenticationFilter —
                // nếu token hợp lệ thì SecurityContext đã có auth, filter sau bỏ qua
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }
}
