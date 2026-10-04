package com.quiktech.pos.filter;

import com.quiktech.pos.security.ClientIpResolver;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

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

        if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(path)) {
            bucketKey = "rl:login:" + ip;
            config = loginConfig;
            policy = "login";
        } else if ("POST".equals(request.getMethod()) && "/api/auth/register".equals(path)) {
            bucketKey = "rl:register:" + ip;
            config = registerConfig;
            policy = "register";
        } else if ("POST".equals(request.getMethod()) && "/api/auth/refresh".equals(path)) {
            bucketKey = "rl:refresh:" + ip;
            config = refreshConfig;
            policy = "refresh";
        } else if (path.startsWith("/api/")) {
            // Coarse per-IP ceiling for every application API request. Authenticated
            // traffic receives a second, fairer per-user limit after JWT validation.
            bucketKey = "rl:ip:api:" + ip;
            config = apiIpConfig;
            policy = "api";
        }

        if (bucketKey != null) {
            RateLimitService.Decision decision = rateLimitService.check(bucketKey, config, "ip", policy);
            if (decision != null && !decision.probe().isConsumed()) {
                RateLimitResponseWriter.write(response, decision.probe(), decision.limit());
                return;
            }
        }

        chain.doFilter(request, response);
    }

}
