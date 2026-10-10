package com.quiktech.pos.security;

import com.quiktech.pos.config.RateLimiterConfig;
import com.quiktech.pos.exception.RateLimitExceededException;
import com.quiktech.pos.filter.RateLimitMetrics;
import com.quiktech.pos.filter.RateLimitService;
import com.quiktech.pos.support.InMemoryRateLimitRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptLimiterTest {

    private final InMemoryRateLimitRedis redis = new InMemoryRateLimitRedis();
    private final RateLimitService service = new RateLimitService(
            new RateLimiterConfig().rateLimitProxyManager(redis.connection(), 100),
            new RateLimitMetrics(new SimpleMeterRegistry()), 1, 5000);
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(service, 2, 900);

    @Test
    void blocksOnlyAfterTheConfiguredNumberOfFailures() {
        String account = LoginAttemptLimiter.accountKey(7L, "owner");
        assertThatCode(() -> limiter.assertAllowed(account)).doesNotThrowAnyException();
        // assertAllowed không trừ lượt — gọi bao nhiêu lần cũng không tự khóa tài khoản
        assertThatCode(() -> limiter.assertAllowed(account)).doesNotThrowAnyException();

        limiter.recordFailure(account);
        assertThatCode(() -> limiter.assertAllowed(account)).doesNotThrowAnyException();
        limiter.recordFailure(account);

        assertThatThrownBy(() -> limiter.assertAllowed(account))
                .isInstanceOfSatisfying(RateLimitExceededException.class, ex -> {
                    assertThat(ex.getLimit()).isEqualTo(2);
                    assertThat(ex.getRetryAfterSeconds()).isPositive();
                });
        // Tài khoản khác không bị ảnh hưởng
        assertThatCode(() -> limiter.assertAllowed(LoginAttemptLimiter.accountKey(8L, "staff"))).doesNotThrowAnyException();
    }

    @Test
    void keysExistingAccountsByIdAndUnknownNamesByCaseInsensitiveHash() {
        assertThat(LoginAttemptLimiter.accountKey(7L, "owner")).isEqualTo(LoginAttemptLimiter.accountKey(7L, "owner@example.com"));
        assertThat(LoginAttemptLimiter.accountKey(null, " Ghost ")).isEqualTo(LoginAttemptLimiter.accountKey(null, "ghost"))
                .startsWith("name:")
                .doesNotContain("ghost")
                .hasSize("name:".length() + 64);
    }
}
