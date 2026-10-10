package com.quiktech.pos.security;

import com.quiktech.pos.exception.RateLimitExceededException;
import com.quiktech.pos.filter.RateLimitResponseWriter;
import com.quiktech.pos.filter.RateLimitService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Giới hạn số lần đăng nhập <b>sai</b> theo tài khoản — bổ sung cho quota login theo IP
 * ({@code RateLimitFilter}), vốn không chặn được dò mật khẩu một tài khoản từ nhiều IP (botnet).
 *
 * <p>Chỉ lần sai mới bị tính: trước khi xác thực gọi {@link #assertAllowed} (chỉ xem, không trừ),
 * sai mật khẩu mới gọi {@link #recordFailure}. Hết lượt thì trả 429 tới khi bucket nạp lại —
 * không khóa hẳn tài khoản, nên người khác cố tình nhập sai cũng không khóa vĩnh viễn được tài
 * khoản của nạn nhân. Mặc định 5 lần sai / 15 phút ({@code rate-limit.login-account.*}).
 *
 * <p>Redis lỗi thì dùng bucket cục bộ của từng instance (localFallback) thay vì bỏ chặn.
 */
@Component
public class LoginAttemptLimiter {

    private static final String POLICY = "login-account";

    private final RateLimitService rateLimitService;
    private final BucketConfiguration config;

    public LoginAttemptLimiter(RateLimitService rateLimitService,
                               @Value("${rate-limit.login-account.max-failures:5}") int maxFailures,
                               @Value("${rate-limit.login-account.window-seconds:900}") int windowSeconds) {
        this.rateLimitService = rateLimitService;
        this.config = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(maxFailures)
                        .refillIntervally(maxFailures, Duration.ofSeconds(windowSeconds))
                        .build())
                .build();
    }

    /**
     * Khóa của tài khoản: userId nếu tồn tại (username và email của cùng tài khoản dùng chung
     * một bucket); không tồn tại thì SHA-256 của chuỗi đã nhập — chuỗi dài tùy ý do client gửi
     * không được thành key Redis, và vẫn giới hạn được việc dò tài khoản không có thật.
     */
    public static String accountKey(Long userId, String usernameOrEmail) {
        if (userId != null) return "user:" + userId;
        return "name:" + sha256(usernameOrEmail.trim().toLowerCase(Locale.ROOT));
    }

    /** Ném {@link RateLimitExceededException} nếu tài khoản đã hết lượt nhập sai. */
    public void assertAllowed(String accountKey) {
        var decision = rateLimitService.peek(bucketKey(accountKey), config, "account", POLICY, true);
        if (decision != null && !decision.probe().isConsumed()) {
            throw new RateLimitExceededException("Too many failed login attempts. Please try again later.",
                    RateLimitResponseWriter.retryAfterSeconds(decision.probe()), decision.limit(),
                    decision.probe().getRemainingTokens());
        }
    }

    /** Trừ một lượt sau khi đăng nhập sai mật khẩu. */
    public void recordFailure(String accountKey) {
        rateLimitService.check(bucketKey(accountKey), config, "account", POLICY, true);
    }

    private static String bucketKey(String accountKey) {
        return "rl:" + POLICY + ":" + accountKey;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
