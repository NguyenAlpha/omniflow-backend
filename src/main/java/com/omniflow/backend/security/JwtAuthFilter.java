package com.omniflow.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Filter xác thực JWT — chạy một lần trên mỗi request, không gọi DB.
 *
 * <h3>Nguyên tắc hoạt động</h3>
 * Mọi thông tin cần thiết ({@code userId}, {@code username}, global roles) được extract
 * thẳng từ JWT claims. Không có {@code UserDetailsService}, không có DB call.
 * Principal được đặt vào {@code SecurityContext} là {@link UserPrincipal} —
 * một record nhỏ gọn thay vì toàn bộ {@code User} entity.
 *
 * <h3>Luồng xử lý</h3>
 * <pre>
 * Request đến
 *   ↓
 * Có header "Authorization: Bearer ..." ?
 *   → Không  → bỏ qua, chuyển filter chain (Spring Security chặn sau nếu endpoint cần auth)
 *   → Có
 *      ↓
 * SecurityContext đã có Authentication ?
 *   → Có  → bỏ qua (tránh ghi đè nếu filter chạy lại trong cùng request)
 *   → Không
 *      ↓
 * Token hợp lệ (chữ ký + chưa hết hạn) ?
 *   → Không  → bỏ qua (Spring Security sẽ trả 401)
 *   → Có
 *      ↓
 * Extract username, userId, roles từ claims
 *      ↓
 * Build UserPrincipal + authorities
 *      ↓
 * Ghi vào SecurityContext → chuyển filter chain
 * </pre>
 *
 * <h3>Thành phần liên quan</h3>
 * <ul>
 *   <li>{@link JwtService} — parse và xác minh token, extract claims</li>
 *   <li>{@link UserPrincipal} — principal được đặt vào {@code SecurityContext}</li>
 *   <li>{@link com.omniflow.backend.config.SecurityConfig} — đăng ký filter này
 *       chạy trước {@code UsernamePasswordAuthenticationFilter}</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    /**
     * Xử lý xác thực JWT cho mỗi request.
     *
     * <p>Dù token hợp lệ hay không, filter luôn gọi {@code filterChain.doFilter()} ở cuối —
     * quyết định từ chối request ({@code 401 Unauthorized}) do Spring Security đảm nhiệm
     * tại bước {@code authorizeHttpRequests} tiếp theo trong chain.
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        // Không có token hoặc sai format — public endpoint hoặc sẽ bị chặn ở bước authorize
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Cắt bỏ prefix "Bearer " (7 ký tự) để lấy JWT thuần
        String token = authHeader.substring(7);

        // Đã có auth trong context — tránh ghi đè nếu filter vô tình chạy lại
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Kiểm tra chữ ký + hạn — parse toàn bộ token trong 1 bước, không gọi DB
        if (!jwtService.isTokenValid(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = jwtService.extractUsername(token);
        Long userId     = jwtService.extractUserId(token);
        List<String> roles = jwtService.extractRoles(token);

        // userId hoặc username null không nên xảy ra với token hợp lệ — guard phòng thủ
        if (username == null || userId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Prefix "ROLE_" là quy ước của Spring Security cho hasRole() / @PreAuthorize("hasRole(...)")
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();

        UserPrincipal principal = new UserPrincipal(userId, username, roles);

        // credentials = null vì đã xác thực qua token, không cần password
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(principal, null, authorities);

        // Gắn thêm thông tin request (IP, session ID) vào auth — hữu ích cho audit log
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // Ghi vào SecurityContext — từ đây request được coi là đã xác thực
        SecurityContextHolder.getContext().setAuthentication(authToken);

        filterChain.doFilter(request, response);
    }
}
