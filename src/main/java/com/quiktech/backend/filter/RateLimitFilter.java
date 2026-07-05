package com.quiktech.backend.filter;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final LettuceBasedProxyManager<byte[]> rateLimitProxyManager;

    @Value("${rate-limit.login.max-requests:10}")
    private int loginMaxRequests;
    @Value("${rate-limit.login.window-seconds:60}")
    private int loginWindowSeconds;
    @Value("${rate-limit.register.max-requests:5}")
    private int registerMaxRequests;
    @Value("${rate-limit.register.window-seconds:60}")
    private int registerWindowSeconds;

    private BucketConfiguration loginConfig;
    private BucketConfiguration registerConfig;

    // JSON response cố định khớp với format ApiResult của codebase:
    // {"success":false,"data":null,"error":{"code":"RATE_LIMIT_EXCEEDED","message":"...","field":null}}
    private static final String RATE_LIMIT_BODY = """
            {"success":false,"data":null,"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests. Please try again later.","field":null}}""";

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
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        String ip = extractIp(request);
        String bucketKey = null;
        BucketConfiguration config = null;

        if (uri.endsWith("/api/auth/login")) {
            bucketKey = "rl:login:" + ip;
            config = loginConfig;
        } else if (uri.endsWith("/api/auth/register")) {
            bucketKey = "rl:register:" + ip;
            config = registerConfig;
        }

        if (bucketKey != null) {
            byte[] key = bucketKey.getBytes(StandardCharsets.UTF_8);
            final BucketConfiguration finalConfig = config;
            Bucket bucket = rateLimitProxyManager.builder().build(key, () -> finalConfig);

            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(retryAfter));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(RATE_LIMIT_BODY);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
