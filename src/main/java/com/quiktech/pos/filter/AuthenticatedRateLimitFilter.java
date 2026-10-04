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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;

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
    private final LettuceBasedProxyManager<byte[]> rateLimitProxyManager;
    private final RateLimitMetrics rateLimitMetrics;

    @Value("${rate-limit.api.user.max-requests:300}")
    private int maxRequests;

    @Value("${rate-limit.api.user.window-seconds:60}")
    private int windowSeconds;
    @Value("${rate-limit.import-products.user.max-requests:5}")
    private int importProductsMaxRequests;
    @Value("${rate-limit.import-products.user.window-seconds:600}")
    private int importProductsWindowSeconds;
    @Value("${rate-limit.export.user.max-requests:10}")
    private int exportMaxRequests;
    @Value("${rate-limit.export.user.window-seconds:600}")
    private int exportWindowSeconds;
    @Value("${rate-limit.change-password.user.max-requests:5}")
    private int changePasswordMaxRequests;
    @Value("${rate-limit.change-password.user.window-seconds:600}")
    private int changePasswordWindowSeconds;

    private BucketConfiguration apiConfig;
    private BucketConfiguration importProductsConfig;
    private BucketConfiguration exportConfig;
    private BucketConfiguration changePasswordConfig;

    public AuthenticatedRateLimitFilter(LettuceBasedProxyManager<byte[]> rateLimitProxyManager,
                                        RateLimitMetrics rateLimitMetrics) {
        this.rateLimitProxyManager = rateLimitProxyManager;
        this.rateLimitMetrics = rateLimitMetrics;
    }

    @PostConstruct
    void initBucketConfig() {
        apiConfig = bucketConfig(maxRequests, windowSeconds);
        importProductsConfig = bucketConfig(importProductsMaxRequests, importProductsWindowSeconds);
        exportConfig = bucketConfig(exportMaxRequests, exportWindowSeconds);
        changePasswordConfig = bucketConfig(changePasswordMaxRequests, changePasswordWindowSeconds);
    }

    private BucketConfiguration bucketConfig(int capacity, int refillSeconds) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillIntervally(capacity, Duration.ofSeconds(refillSeconds))
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

        RateLimitRule rule = resolveRule(request);
        String bucketKey = "rl:user:" + rule.name() + ":" + principal.userId();
        ConsumptionProbe probe = null;
        try {
            Bucket bucket = rateLimitProxyManager.builder().build(
                    bucketKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), () -> rule.config());
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (Exception e) {
            // Redis unavailable must not turn every authenticated API request into a 500.
            log.warn("Authenticated rate limit check failed — failing open for userId={}", principal.userId(), e);
            rateLimitMetrics.record("user", rule.name(), "error");
        }

        if (probe != null && !probe.isConsumed()) {
            rateLimitMetrics.record("user", rule.name(), "blocked");
            RateLimitResponseWriter.write(response, probe, rule.limit());
            return;
        }
        if (probe != null) {
            rateLimitMetrics.record("user", rule.name(), "allowed");
        }

        chain.doFilter(request, response);
    }

    private RateLimitRule resolveRule(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        String method = request.getMethod();

        if ("POST".equals(method) && path.matches("^/api/businesses/[^/]+/products/import$")) {
            return new RateLimitRule("import-products", importProductsConfig, importProductsMaxRequests);
        }
        if ("GET".equals(method) && path.matches("^/api/stores/[^/]+/export/.+$")) {
            return new RateLimitRule("export", exportConfig, exportMaxRequests);
        }
        if ("PATCH".equals(method) && "/api/users/me/password".equals(path)) {
            return new RateLimitRule("change-password", changePasswordConfig, changePasswordMaxRequests);
        }
        return new RateLimitRule("api", apiConfig, maxRequests);
    }

    private record RateLimitRule(String name, BucketConfiguration config, int limit) {
    }
}
