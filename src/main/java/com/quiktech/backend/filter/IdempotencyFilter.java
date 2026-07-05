package com.quiktech.backend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Idempotency filter cho POST /api/stores/{id}/orders.
 * Client gửi header "Idempotency-Key: <uuid>" — nếu key đã tồn tại trong Redis,
 * trả về response đã cache mà không xử lý lại. Giải quyết duplicate order khi retry.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private final StringRedisTemplate stringRedisTemplate;

    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idem:";
    private static final int MAX_KEY_LENGTH = 128;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String rawKey = request.getHeader("Idempotency-Key");
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > MAX_KEY_LENGTH
                || !request.getRequestURI().matches(".*/api/stores/\\d+/orders/?$")) {
            chain.doFilter(request, response);
            return;
        }

        String redisKey = KEY_PREFIX + rawKey;

        String cached = stringRedisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("X-Idempotency-Cached", "true");
            response.getWriter().write(cached);
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, responseWrapper);

        int status = responseWrapper.getStatus();
        if (status >= 200 && status < 300) {
            String body = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
            if (!body.isBlank()) {
                stringRedisTemplate.opsForValue().set(redisKey, body, TTL);
            }
        }

        responseWrapper.copyBodyToResponse();
    }
}
