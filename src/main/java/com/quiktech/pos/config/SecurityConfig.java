package com.quiktech.pos.config;

import com.quiktech.pos.security.StoreAccessEvaluator;
import com.quiktech.pos.security.UserPrincipalConverter;
import com.quiktech.pos.filter.AuthenticatedRateLimitFilter;
import com.quiktech.pos.filter.IdempotencyFilter;
import com.quiktech.pos.filter.RateLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;

/**
 * Cấu hình HTTP Security filter chain cho toàn bộ ứng dụng.
 *
 * <p><b>Chức năng chính:</b>
 * <ul>
 *   <li>Xác định endpoint nào public, endpoint nào yêu cầu xác thực</li>
 *   <li>Cấu hình stateless session (JWT — không dùng HttpSession)</li>
 *   <li>Kích hoạt OAuth2 Resource Server — Spring Security tự xử lý Bearer token qua
 *       {@link org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationFilter}</li>
 *   <li>Bật method-level security cho {@code @PreAuthorize}</li>
 * </ul>
 *
 * <p><b>Thành phần liên quan:</b>
 * <ul>
 *   <li>{@link ApplicationConfig} — cung cấp {@code JwtDecoder} và {@code jwtAuthConverter}</li>
 *   <li>{@link UserPrincipalConverter} — convert JWT thành
 *       {@code UserPrincipal} lưu vào SecurityContext, thay thế cho JwtAuthFilter cũ</li>
 *   <li>{@link StoreAccessEvaluator} — được kích hoạt bởi
 *       {@code @EnableMethodSecurity} thông qua {@code @PreAuthorize("@storeAccess....")}</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
// Bật @PreAuthorize, @PostAuthorize trên method — cần thiết cho StoreAccessEvaluator
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final Converter<Jwt, AbstractAuthenticationToken> jwtAuthConverter;

    // JSON response cố định khớp với format ApiResult của codebase (cùng pattern với
    // RATE_LIMIT_BODY trong RateLimitFilter):
    // {"success":false,"data":null,"error":{"code":"UNAUTHORIZED","message":"...","field":null}}
    private static final String UNAUTHORIZED_BODY = """
            {"success":false,"data":null,"error":{"code":"UNAUTHORIZED","message":"Authentication required. Provide a valid Bearer token.","field":null}}""";

    /**
     * Định nghĩa filter chain chính xử lý mọi HTTP request.
     * Thứ tự các bước cấu hình phản ánh thứ tự xử lý thực tế của Spring Security.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticatedRateLimitFilter authenticatedRateLimitFilter,
                                                   IdempotencyFilter idempotencyFilter) throws Exception {
        return http
                .cors(AbstractHttpConfigurer::disable)
                // CSRF không cần thiết với stateless JWT — không có cookie session để exploit
                .csrf(AbstractHttpConfigurer::disable)

                // config quyền truy cập API
                .authorizeHttpRequests(auth -> auth
                        // Auth endpoints công khai — đăng ký / đăng nhập / refresh không cần token
                        // logout yêu cầu JWT hợp lệ (không trong danh sách này)
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/refresh").permitAll()
                        // Bảng giá/giới hạn gói — trang landing hiển thị cho khách chưa đăng nhập
                        .requestMatchers(HttpMethod.GET, "/api/plans").permitAll()
                        // /actuator/** chỉ còn trên management port (mạng nội bộ, xem management.server.port);
                        // /livez, /readyz là health probe trên port chính cho Docker/load balancer
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/livez", "/readyz").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // Mọi request còn lại bắt buộc phải có JWT hợp lệ
                        .anyRequest().authenticated()
                )

                // Không tạo hay lưu HttpSession — mỗi request tự xác thực qua JWT
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Trả về 401 JSON-friendly thay vì redirect đến trang login mặc định của Spring.
                // Ghi thẳng body ApiResult (giống RateLimitFilter) thay vì sendError() —
                // sendError() trả về trang HTML lỗi mặc định của servlet container,
                // không khớp envelope {success,data,error} mà client parse.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.getWriter().write(UNAUTHORIZED_BODY);
                        })
                )

                // Kích hoạt BearerTokenAuthenticationFilter — tự động validate JWT và đưa
                // UserPrincipal vào SecurityContext qua jwtAuthConverter
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                )

                // Phải chạy sau BearerTokenAuthenticationFilter để quota theo userId đã
                // được xác thực, không theo IP dùng chung của nhiều máy trong một store.
                .addFilterAfter(authenticatedRateLimitFilter, BearerTokenAuthenticationFilter.class)
                // Sau rate limit theo user: response đã lưu chỉ trả cho request có JWT hợp lệ,
                // và request trùng vẫn bị tính quota
                .addFilterAfter(idempotencyFilter, AuthenticatedRateLimitFilter.class)

                .build();
    }

    /**
     * Filter này chỉ thuộc Spring Security filter chain. Disable container registration
     * để nó không chạy thêm lần nữa trước khi JWT được xác thực.
     */
    @Bean
    public static FilterRegistrationBean<AuthenticatedRateLimitFilter> authenticatedRateLimitFilterRegistration(
            AuthenticatedRateLimitFilter filter) {
        FilterRegistrationBean<AuthenticatedRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public AuthenticatedRateLimitFilter authenticatedRateLimitFilter(RateLimitService rateLimitService) {
        return new AuthenticatedRateLimitFilter(rateLimitService);
    }

    /** Giống AuthenticatedRateLimitFilter: chỉ chạy trong Spring Security chain, sau khi xác thực JWT. */
    @Bean
    public static FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(IdempotencyFilter filter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public IdempotencyFilter idempotencyFilter(StringRedisTemplate stringRedisTemplate) {
        return new IdempotencyFilter(stringRedisTemplate);
    }
}
