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
 *
 * <p>Việc đếm/trừ lượt thực tế nằm ở {@link RateLimitService} (Bucket4j trên Redis) — class này
 * chỉ định nghĩa quota và cách đặt khóa. Nơi gọi duy nhất: {@code AuthService.login}.
 */
@Component
public class LoginAttemptLimiter {

    // Tên quota: vừa là một phần của key Redis (xem bucketKey) vừa là tag "policy" của metric rate limit
    private static final String POLICY = "login-account";

    private final RateLimitService rateLimitService;
    private final BucketConfiguration config;

    /**
     * Dựng cấu hình bucket dùng chung cho mọi tài khoản.
     *
     * <p>Mỗi tài khoản có {@code maxFailures} lượt; cứ mỗi {@code windowSeconds} giây được nạp lại
     * <b>toàn bộ</b> lượt cùng một lúc ({@code refillIntervally}) — không nạp dần từng lượt. Vì vậy
     * khi đã hết lượt, phải chờ tới mốc nạp kế tiếp (thời gian chờ trả về trong header {@code Retry-After}).
     *
     * <p>Ghi đè qua biến môi trường {@code RATE_LIMIT_LOGIN_ACCOUNT_MAX_FAILURES} /
     * {@code RATE_LIMIT_LOGIN_ACCOUNT_WINDOW_SECONDS} (xem {@code .env.example}); không có thì dùng
     * giá trị mặc định trong {@code @Value} bên dưới.
     *
     * @param rateLimitService service đếm/trừ lượt trên Redis, dùng chung với các rate limit khác
     * @param maxFailures      số lần đăng nhập sai tối đa ({@code rate-limit.login-account.max-failures})
     * @param windowSeconds    chu kỳ nạp lại lượt, tính bằng giây ({@code rate-limit.login-account.window-seconds})
     */
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
     *
     * @param userId          ID của user tìm được theo chuỗi đã nhập; {@code null} nếu không tồn tại
     * @param usernameOrEmail chuỗi người dùng nhập ở ô đăng nhập
     * @return khóa dạng {@code user:<id>} hoặc {@code name:<sha256>} — truyền vào
     *         {@link #assertAllowed} và {@link #recordFailure}
     */
    public static String accountKey(Long userId, String usernameOrEmail) {
        // Tiền tố "user:" / "name:" tách 2 loại khóa — không thể có chuỗi nhập nào trùng khóa của một userId
        if (userId != null) return "user:" + userId;
        // trim + lowercase trước khi băm: " An@X.com" và "an@x.com" dùng chung một bucket,
        // không lách được giới hạn bằng cách đổi hoa/thường hay thêm khoảng trắng
        return "name:" + sha256(usernameOrEmail.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Ném {@link RateLimitExceededException} nếu tài khoản đã hết lượt nhập sai.
     *
     * <p>Chỉ <b>xem</b> còn lượt hay không, không trừ lượt — gọi trước khi kiểm tra mật khẩu.
     * Lượt chỉ bị trừ khi sai mật khẩu ({@link #recordFailure}).
     *
     * @param accountKey khóa tài khoản lấy từ {@link #accountKey}
     * @throws RateLimitExceededException đã hết lượt (→ 429 {@code RATE_LIMIT_EXCEEDED}, kèm header
     *                                    {@code Retry-After} và {@code RateLimit-*} do
     *                                    {@code GlobalExceptionHandler} thêm vào)
     */
    public void assertAllowed(String accountKey) {
        // peek = xem không trừ; "account" là tag scope của metric; true = bật localFallback
        // (Redis lỗi vẫn chặn bằng bucket trong bộ nhớ thay vì cho qua — xem RateLimitService.check)
        var decision = rateLimitService.peek(bucketKey(accountKey), config, "account", POLICY, true);
        // Với peek, probe().isConsumed() nghĩa là "còn ít nhất 1 lượt". decision chỉ null khi bỏ qua
        // kiểm tra mà localFallback = false — ở đây luôn bật nên điều kiện null chỉ là phòng thủ.
        if (decision != null && !decision.probe().isConsumed()) {
            throw new RateLimitExceededException("Too many failed login attempts. Please try again later.",
                    RateLimitResponseWriter.retryAfterSeconds(decision.probe()), decision.limit(),
                    decision.probe().getRemainingTokens());
        }
    }

    /**
     * Trừ một lượt sau khi đăng nhập sai mật khẩu.
     *
     * @param accountKey khóa tài khoản lấy từ {@link #accountKey}
     */
    public void recordFailure(String accountKey) {
        // Bỏ qua kết quả trả về: lần đăng nhập này đã thất bại (401) rồi; nếu vừa dùng hết lượt thì
        // lần thử kế tiếp sẽ bị assertAllowed chặn
        rateLimitService.check(bucketKey(accountKey), config, "account", POLICY, true);
    }

    // Key Redis của bucket, VD "rl:login-account:user:42" — cùng tiền tố "rl:" với các rate limit khác
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
