package com.quiktech.pos.filter;

import com.quiktech.pos.security.UserPrincipal;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rate limit cho request API đã được Spring Security xác thực.
 *
 * <p>Filter này phải nằm sau {@code BearerTokenAuthenticationFilter}; khi đó
 * {@link UserPrincipal} đã có trong {@code SecurityContext} và quota được tính
 * theo user thay vì IP. Nhờ vậy nhiều nhân viên dùng chung mạng cửa hàng không
 * tự chặn nhau.
 *
 * <p>Không đánh rate limit request chưa xác thực tại đây. Chúng được xử lý bởi
 * {@link RateLimitFilter}, chạy ở lớp servlet trước Spring Security.
 */
@Slf4j
public class AuthenticatedRateLimitFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;
    private static final String RATE_LIMIT_BODY = """
            {"success":false,"data":null,"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests. Please try again later.","field":null}}""";

    private final LettuceBasedProxyManager<byte[]> rateLimitProxyManager;

    @Value("${rate-limit.api.user.max-requests:300}")
    private int maxRequests;

    @Value("${rate-limit.api.user.window-seconds:60}")
    private int windowSeconds;

    private BucketConfiguration config;

    public AuthenticatedRateLimitFilter(LettuceBasedProxyManager<byte[]> rateLimitProxyManager) {
        this.rateLimitProxyManager = rateLimitProxyManager;
    }

    @PostConstruct
    void initBucketConfig() {
        config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(maxRequests)
                        .refillIntervally(maxRequests, Duration.ofSeconds(windowSeconds))
                        .build())
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equals(request.getMethod()) || !PATH_HELPER.getPathWithinApplication(request).startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        String bucketKey = "rl:user:api:" + principal.userId();
        ConsumptionProbe probe = null;
        try {
            Bucket bucket = rateLimitProxyManager.builder().build(
                    bucketKey.getBytes(StandardCharsets.UTF_8), () -> config);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (Exception e) {
            // Redis unavailable must not turn every authenticated API request into a 500.
            log.warn("Authenticated rate limit check failed — failing open for userId={}", principal.userId(), e);
        }

        if (probe != null && !probe.isConsumed()) {
            long retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(RATE_LIMIT_BODY);
            return;
        }

        chain.doFilter(request, response);
    }
}
