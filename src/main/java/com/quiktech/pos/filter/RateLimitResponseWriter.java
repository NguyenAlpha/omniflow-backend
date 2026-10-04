package com.quiktech.pos.filter;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Writes the common API response when a Bucket4j quota is exhausted. */
final class RateLimitResponseWriter {

    private static final String RATE_LIMIT_BODY = """
            {"success":false,"data":null,"error":{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests. Please try again later.","field":null}}""";

    private RateLimitResponseWriter() {
    }

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
