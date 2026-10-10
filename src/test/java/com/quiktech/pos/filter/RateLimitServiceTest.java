package com.quiktech.pos.filter;

import com.quiktech.pos.config.RateLimiterConfig;
import com.quiktech.pos.support.InMemoryRateLimitRedis;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitServiceTest {

    private final InMemoryRateLimitRedis redis = new InMemoryRateLimitRedis();
    private final LettuceBasedProxyManager<byte[]> manager =
            new RateLimiterConfig().rateLimitProxyManager(redis.connection(), 100);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimitMetrics metrics = new RateLimitMetrics(registry);

    @Test
    void migratesLegacyBucketWithoutRestoringExhaustedTokens() {
        manager.builder().build("legacy".getBytes(StandardCharsets.UTF_8), config(10)).tryConsume(10);
        var service = new RateLimitService(manager, metrics, 1, 5000);

        var result = service.check("legacy", config(2), "user", "api");

        assertThat(result.limit()).isEqualTo(2);
        assertThat(result.probe().isConsumed()).isFalse();
    }

    @Test
    void updatesQuotaProportionallyAndOldInstanceCannotDowngradeIt() {
        var oldInstance = new RateLimitService(manager, metrics, 1, 5000);
        for (int count = 0; count < 5; count++) {
            assertThat(oldInstance.check("user", config(10), "user", "api").probe().isConsumed()).isTrue();
        }
        var newInstance = new RateLimitService(manager, metrics, 2, 5000);

        var changed = newInstance.check("user", config(2), "user", "api");
        assertThat(changed.limit()).isEqualTo(2);
        assertThat(changed.probe().isConsumed()).isTrue();
        assertThat(changed.probe().getRemainingTokens()).isZero();

        var oldRequest = oldInstance.check("user", config(10), "user", "api");
        assertThat(oldRequest.limit()).isEqualTo(2);
        assertThat(oldRequest.probe().isConsumed()).isFalse();
    }

    @Test
    @Timeout(5)
    void boundsRedisWaitAndSkipsBothLayersDuringCooldownThenRecovers() {
        var clock = new AtomicLong(1000);
        var service = new RateLimitService(manager, metrics, 1, 5000, clock::get);
        redis.pause();

        long started = System.nanoTime();
        assertThat(service.check("ip", config(10), "ip", "api")).isNull();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        int readsAfterFailure = redis.reads();
        assertThat(service.check("user", config(10), "user", "api")).isNull();
        assertThat(redis.reads()).isEqualTo(readsAfterFailure);
        assertThat(registry.get("rate_limit_requests_total").tag("outcome", "error").counter().count()).isEqualTo(1);
        assertThat(registry.get("rate_limit_requests_total").tag("outcome", "bypassed").counter().count()).isEqualTo(1);

        clock.addAndGet(5000);
        redis.resume();
        assertThat(service.check("user", config(10), "user", "api").probe().isConsumed()).isTrue();
    }

    @Test
    @Timeout(10)
    void sharesQuotaBetweenConcurrentInstancesAndSeparatesUsers() throws Exception {
        var first = new RateLimitService(manager, metrics, 1, 5000);
        var second = new RateLimitService(manager, metrics, 1, 5000);
        var allowed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int request = 0; request < 30; request++) {
                var service = request % 2 == 0 ? first : second;
                executor.submit(() -> {
                    var decision = service.check("shared-user", config(10), "user", "api");
                    if (decision != null && decision.probe().isConsumed()) {
                        allowed.incrementAndGet();
                    }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(allowed.get()).isEqualTo(10);
        assertThat(second.check("different-user", config(10), "user", "api").probe().isConsumed()).isTrue();
    }

    @Test
    void refillsAfterConfiguredWindow() {
        var nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        TimeMeter clock = new TimeMeter() {
            public long currentTimeNanos() { return nanos.get(); }
            public boolean isWallClockBased() { return true; }
        };
        var timedManager = LettuceBasedProxyManager.builderFor(redis.connection())
                .withClientSideConfig(ClientSideConfig.getDefault().withClientClock(clock))
                .withExpirationStrategy(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build();
        var service = new RateLimitService(timedManager, metrics, 1, 5000);
        assertThat(service.check("refill", config(1), "user", "api").probe().isConsumed()).isTrue();
        assertThat(service.check("refill", config(1), "user", "api").probe().isConsumed()).isFalse();
        nanos.addAndGet(TimeUnit.MINUTES.toNanos(1));
        assertThat(service.check("refill", config(1), "user", "api").probe().isConsumed()).isTrue();
    }

    @Test
    void peekReportsQuotaWithoutConsumingIt() {
        var service = new RateLimitService(manager, metrics, 1, 5000);
        for (int peek = 0; peek < 3; peek++) {
            assertThat(service.peek("peek", config(1), "account", "login-account", false).probe().isConsumed()).isTrue();
        }
        assertThat(service.check("peek", config(1), "account", "login-account").probe().isConsumed()).isTrue();
        var exhausted = service.peek("peek", config(1), "account", "login-account", false);
        assertThat(exhausted.probe().isConsumed()).isFalse();
        assertThat(exhausted.probe().getNanosToWaitForRefill()).isPositive();
    }

    @Test
    @Timeout(5)
    void localFallbackKeepsLimitingAuthQuotasWhileRedisIsDown() {
        var clock = new AtomicLong(1000);
        var service = new RateLimitService(manager, metrics, 1, 5000, clock::get);
        redis.pause();

        // Lần đầu Redis lỗi → chuyển sang bucket cục bộ ngay trong cùng request
        assertThat(service.check("rl:login:ip", config(1), "ip", "login", true).probe().isConsumed()).isTrue();
        // Đang cooldown → vẫn dùng bucket cục bộ, đã hết token nên chặn
        assertThat(service.check("rl:login:ip", config(1), "ip", "login", true).probe().isConsumed()).isFalse();
        assertThat(registry.get("rate_limit_requests_total").tag("outcome", "fallback_blocked").counter().count()).isEqualTo(1);
        // Quota không bật fallback vẫn fail-open như cũ
        assertThat(service.check("rl:ip:api:ip", config(1), "ip", "api")).isNull();
        redis.resume();
    }

    @Test
    void rejectsInvalidOperationalSettings() {
        assertThatThrownBy(() -> new RateLimiterConfig().rateLimitProxyManager(redis.connection(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitService(manager, metrics, 0, 5000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitService(manager, metrics, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private BucketConfiguration config(int capacity) {
        return BucketConfiguration.builder().addLimit(Bandwidth.builder().capacity(capacity)
                .refillIntervally(capacity, Duration.ofMinutes(1)).build()).build();
    }
}
