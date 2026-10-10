package com.quiktech.pos.filter;

import com.quiktech.pos.security.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Idempotency filter cho POST /api/stores/{id}/orders.
 * Client gửi header "Idempotency-Key: <uuid>" — nếu key đã tồn tại trong Redis,
 * trả về response đã cache mà không xử lý lại. Giải quyết duplicate order khi retry.
 *
 * <p><b>Đăng ký:</b> class không có {@code @Component}. {@code SecurityConfig} gắn filter vào
 * chuỗi filter của Spring Security ngay sau {@link AuthenticatedRateLimitFilter} (tức sau khi
 * JWT đã được xác thực) và tắt đăng ký servlet tự động — response đã lưu chỉ được trả lại
 * cho request có JWT hợp lệ. Request chưa xác thực đi thẳng để Spring Security trả 401.
 *
 * <p><b>Luồng xử lý:</b>
 * <ol>
 *   <li>Chỉ áp dụng cho {@code POST} tới {@code /api/stores/{số}/orders} (có thể có {@code /} cuối),
 *       đã xác thực, có header {@code Idempotency-Key} không rỗng, dài tối đa 128 ký tự.</li>
 *   <li>Key Redis gắn với user và store: {@code idem:<userId>:<storeId>:<key>} — hai client trùng
 *       key không nhận response của nhau.</li>
 *   <li>Giữ chỗ bằng {@code SETNX} giá trị {@code PENDING} (TTL 60 giây). Giữ được → xử lý request.
 *       Không giữ được: key đang {@code PENDING} (request trùng đang chạy song song) → 409;
 *       key đã có response → trả lại đúng status + body đã lưu, kèm {@code X-Idempotency-Cached: true}.</li>
 *   <li>Xử lý xong, kết quả 2xx có body thì lưu {@code "<status>:<body>"} trong 24 giờ; còn lại
 *       (4xx/5xx, exception) thì xóa key, để client sửa dữ liệu rồi gửi lại cùng key được.</li>
 * </ol>
 *
 * <p>Redis lỗi thì xử lý request như không có idempotency (fail-open) — không làm hỏng việc tạo
 * đơn, chỉ mất khả năng chống trùng trong lúc Redis sập. Filter không so sánh body của các
 * request cùng key: gửi lại cùng key với body khác vẫn nhận response của lần đầu.
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;
    private static final Pattern ORDER_CREATE_PATH = Pattern.compile("^/api/stores/(\\d+)/orders/?$");

    /** Thời gian giữ response trong Redis — retry sau mốc này sẽ tạo đơn mới. */
    private static final Duration TTL = Duration.ofHours(24);
    /** Thời gian giữ chỗ khi đang xử lý — instance chết giữa chừng thì key tự nhả sau mốc này. */
    private static final Duration PENDING_TTL = Duration.ofSeconds(60);
    private static final String PENDING = "PENDING";
    private static final String KEY_PREFIX = "idem:";
    /** Chặn key quá dài làm phình Redis; key vượt giới hạn bị bỏ qua (xử lý như không có key). */
    private static final int MAX_KEY_LENGTH = 128;

    private static final String IN_PROGRESS_BODY = """
            {"success":false,"data":null,"error":{"code":"IDEMPOTENCY_REQUEST_IN_PROGRESS","message":"A request with this Idempotency-Key is still being processed. Retry later.","field":null}}""";

    private final StringRedisTemplate stringRedisTemplate;

    public IdempotencyFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** Chỉ request {@code POST} mới đi vào {@link #doFilterInternal}. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String rawKey = request.getHeader("Idempotency-Key");
        Matcher path = ORDER_CREATE_PATH.matcher(PATH_HELPER.getPathWithinApplication(request));
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (rawKey == null || rawKey.isBlank() || rawKey.length() > MAX_KEY_LENGTH || !path.matches()
                || authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        String redisKey = KEY_PREFIX + principal.userId() + ":" + path.group(1) + ":" + rawKey;

        Boolean reserved;
        String existing = null;
        try {
            reserved = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL);
            if (!Boolean.TRUE.equals(reserved)) {
                existing = stringRedisTemplate.opsForValue().get(redisKey);
            }
        } catch (RuntimeException redisFailure) {
            log.warn("Idempotency check skipped, Redis unavailable: {}", redisFailure.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (!Boolean.TRUE.equals(reserved)) {
            if (existing == null) {
                // Key vừa hết hạn giữa SETNX và GET — hiếm; xử lý như request bình thường
                chain.doFilter(request, response);
            } else if (PENDING.equals(existing)) {
                writeJson(response, HttpServletResponse.SC_CONFLICT, IN_PROGRESS_BODY, false);
            } else {
                int separator = existing.indexOf(':');
                writeJson(response, Integer.parseInt(existing.substring(0, separator)), existing.substring(separator + 1), true);
            }
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(request, responseWrapper);
            int status = responseWrapper.getStatus();
            String body = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
            if (status >= 200 && status < 300 && !body.isBlank()) {
                try {
                    stringRedisTemplate.opsForValue().set(redisKey, status + ":" + body, TTL);
                    stored = true;
                } catch (RuntimeException redisFailure) {
                    // Đơn đã tạo xong — không được biến thành lỗi cho client chỉ vì không lưu được response
                    log.warn("Could not store idempotent response: {}", redisFailure.getMessage());
                }
            }
        } finally {
            if (!stored) {
                release(redisKey);
            }
        }
        responseWrapper.copyBodyToResponse();
    }

    private void release(String redisKey) {
        try {
            stringRedisTemplate.delete(redisKey);
        } catch (RuntimeException redisFailure) {
            // Không xóa được thì key PENDING tự hết hạn sau PENDING_TTL
            log.warn("Could not release idempotency key: {}", redisFailure.getMessage());
        }
    }

    private static void writeJson(HttpServletResponse response, int status, String body, boolean cached) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (cached) {
            response.setHeader("X-Idempotency-Cached", "true");
        }
        response.getWriter().write(body);
    }
}
