package com.quiktech.pos.filter;

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
 *
 * <p><b>Luồng xử lý:</b>
 * <ol>
 *   <li>Chỉ áp dụng cho {@code POST} tới {@code /api/stores/{số}/orders} (có thể có {@code /} cuối)
 *       và có header {@code Idempotency-Key} không rỗng, dài tối đa 128 ký tự. Request khác đi thẳng.</li>
 *   <li>Key đã có trong Redis ({@code idem:<key>}) → trả lại body đã lưu với HTTP 200 và header
 *       {@code X-Idempotency-Cached: true}, không gọi controller.</li>
 *   <li>Chưa có → bọc response bằng {@link ContentCachingResponseWrapper} để đọc lại được body,
 *       cho request chạy tiếp; nếu kết quả là 2xx và body không rỗng thì lưu body vào Redis 24 giờ.
 *       Response lỗi (4xx/5xx) không được lưu, nên client sửa dữ liệu rồi gửi lại cùng key vẫn được.</li>
 *   <li>Cuối cùng {@code copyBodyToResponse()} chép body đã giữ trong wrapper ra response thật.</li>
 * </ol>
 *
 * <p><b>Lưu ý — giới hạn của cách làm hiện tại:</b>
 * <ul>
 *   <li>Đọc rồi mới ghi Redis (không atomic): hai request cùng key gửi gần như đồng thời đều có
 *       thể chưa thấy key và cùng tạo đơn. Filter chỉ chống được retry tuần tự.</li>
 *   <li>Key dùng chung toàn hệ thống, không gắn với user/store: hai client trùng key sẽ nhận
 *       response của nhau.</li>
 *   <li>Order {@code HIGHEST_PRECEDENCE + 10} chạy trước chuỗi filter của Spring Security (order
 *       mặc định -100), nên response cache được trả lại mà không kiểm tra JWT.</li>
 *   <li>Lần tạo đầu trả 201 Created, lần trả từ cache luôn là 200.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private final StringRedisTemplate stringRedisTemplate;

    /** Thời gian giữ response trong Redis — retry sau mốc này sẽ tạo đơn mới. */
    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idem:";
    /** Chặn key quá dài làm phình Redis; key vượt giới hạn bị bỏ qua (xử lý như không có key). */
    private static final int MAX_KEY_LENGTH = 128;

    /** Chỉ request {@code POST} mới đi vào {@link #doFilterInternal}. */
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
