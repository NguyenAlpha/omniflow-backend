package com.quiktech.pos.filter;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Ghi response chung khi quota Bucket4j đã hết — dùng chung cho cả hai filter rate limit để
 * client nhận cùng một định dạng.
 *
 * <p>Response là HTTP 429 với body theo envelope {@code ApiResult}
 * ({@code error.code = RATE_LIMIT_EXCEEDED}) và các header:
 * <ul>
 *   <li>{@code Retry-After}: số giây phải chờ — web dùng giá trị này để hiện "thử lại sau N giây"</li>
 *   <li>{@code RateLimit-Limit}: capacity của bucket</li>
 *   <li>{@code RateLimit-Remaining}: số token còn lại (thường là 0)</li>
 *   <li>{@code RateLimit-Reset}: số giây tới khi bucket nạp lại, bằng {@code Retry-After}</li>
 * </ul>
 * Body được ghi thẳng (không qua {@code GlobalExceptionHandler}) vì filter chạy ngoài
 * DispatcherServlet.
 */
final class RateLimitResponseWriter {

    private static final String RATE_LIMIT_BODY = """
            {"success":false,"data":null,"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests. Please try again later.","field":null}}""";

    private RateLimitResponseWriter() {
    }

    /**
     * Thời gian chờ được làm tròn <b>lên</b> giây (và tối thiểu 1): làm tròn xuống thì client
     * có thể retry sớm hơn lúc bucket nạp lại và lại nhận 429.
     *
     * @param probe kết quả trừ token thất bại, chứa thời gian chờ nạp lại và token còn lại
     * @param limit capacity của bucket
     */
    static void write(HttpServletResponse response, ConsumptionProbe probe, long limit) throws IOException {
        long nanos = probe.getNanosToWaitForRefill();
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos)
                + (nanos % TimeUnit.SECONDS.toNanos(1) == 0 ? 0 : 1));

        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setHeader("RateLimit-Limit", String.valueOf(limit));
        response.setHeader("RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
        response.setHeader("RateLimit-Reset", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(RATE_LIMIT_BODY);
    }
}
