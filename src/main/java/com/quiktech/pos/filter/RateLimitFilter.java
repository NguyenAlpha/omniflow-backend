package com.quiktech.pos.filter;

import com.quiktech.pos.security.ClientIpResolver;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final LettuceBasedProxyManager<byte[]> rateLimitProxyManager;
    private final ClientIpResolver clientIpResolver;
    private final RateLimitMetrics rateLimitMetrics;

    @Value("${rate-limit.login.max-requests:10}")
    private int loginMaxRequests;
    @Value("${rate-limit.login.window-seconds:60}")
    private int loginWindowSeconds;
    @Value("${rate-limit.register.max-requests:5}")
    private int registerMaxRequests;
    @Value("${rate-limit.register.window-seconds:60}")
    private int registerWindowSeconds;
    @Value("${rate-limit.refresh.max-requests:20}")
    private int refreshMaxRequests;
    @Value("${rate-limit.refresh.window-seconds:60}")
    private int refreshWindowSeconds;
    @Value("${rate-limit.api.ip.max-requests:1200}")
    private int apiIpMaxRequests;
    @Value("${rate-limit.api.ip.window-seconds:60}")
    private int apiIpWindowSeconds;

    /**
     * Decode path trước khi so khớp — so sánh URI thô bằng endsWith có thể bị bypass
     * bằng URL-encoding (VD: /api/auth/logi%6E vẫn route tới /api/auth/login nhưng
     * không match chuỗi thô). UrlPathHelper trả về path đã decode, đã bỏ context path.
     */
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private BucketConfiguration loginConfig;
    private BucketConfiguration registerConfig;
    private BucketConfiguration refreshConfig;
    private BucketConfiguration apiIpConfig;

    @PostConstruct
    void initBucketConfigs() {
        loginConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(loginMaxRequests)
                        .refillIntervally(loginMaxRequests, Duration.ofSeconds(loginWindowSeconds))
                        .build())
                .build();

        registerConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(registerMaxRequests)
                        .refillIntervally(registerMaxRequests, Duration.ofSeconds(registerWindowSeconds))
                        .build())
                .build();

        refreshConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(refreshMaxRequests)
                        .refillIntervally(refreshMaxRequests, Duration.ofSeconds(refreshWindowSeconds))
                        .build())
                .build();

        apiIpConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(apiIpMaxRequests)
                        .refillIntervally(apiIpMaxRequests, Duration.ofSeconds(apiIpWindowSeconds))
                        .build())
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // Path đã decode + bỏ context path — so khớp bằng equals, không dùng endsWith
        // trên URI thô (bypass được bằng URL-encoding)
        String path = PATH_HELPER.getPathWithinApplication(request);
        String ip = clientIpResolver.resolve(request);
        String bucketKey = null;
        BucketConfiguration config = null;
        String policy = null;
        int limit = 0;

        if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(path)) {
            bucketKey = "rl:login:" + ip;
            config = loginConfig;
            policy = "login";
            limit = loginMaxRequests;
        } else if ("POST".equals(request.getMethod()) && "/api/auth/register".equals(path)) {
            bucketKey = "rl:register:" + ip;
            config = registerConfig;
            policy = "register";
            limit = registerMaxRequests;
        } else if ("POST".equals(request.getMethod()) && "/api/auth/refresh".equals(path)) {
            bucketKey = "rl:refresh:" + ip;
            config = refreshConfig;
            policy = "refresh";
            limit = refreshMaxRequests;
        } else if (path.startsWith("/api/")) {
            // Coarse per-IP ceiling for every application API request. Authenticated
            // traffic receives a second, fairer per-user limit after JWT validation.
            bucketKey = "rl:ip:api:" + ip;
            config = apiIpConfig;
            policy = "api";
            limit = apiIpMaxRequests;
        }

        if (bucketKey != null) {
            // Fail-open khi Redis lỗi: log warning và cho request đi qua — nhất quán với
            // graceful degradation của các evaluator. Không try-catch thì Redis down làm
            // toàn bộ login/register trả 500 (mất chức năng đăng nhập thay vì mất rate limit).
            ConsumptionProbe probe = null;
            try {
                byte[] key = bucketKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                final BucketConfiguration finalConfig = config;
                Bucket bucket = rateLimitProxyManager.builder().build(key, () -> finalConfig);
                probe = bucket.tryConsumeAndReturnRemaining(1);
            } catch (Exception e) {
                log.warn("Rate limit check failed (Redis unavailable?) — failing open for {}", bucketKey, e);
                rateLimitMetrics.record("ip", policy, "error");
            }

            if (probe != null && !probe.isConsumed()) {
                rateLimitMetrics.record("ip", policy, "blocked");
                RateLimitResponseWriter.write(response, probe, limit);
                return;
            }
            if (probe != null) {
                rateLimitMetrics.record("ip", policy, "allowed");
            }
        }

        chain.doFilter(request, response);
    }

}
