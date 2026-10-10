package com.quiktech.pos.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Kiểm tra và trừ quota trên bucket Redis — dùng chung cho {@link RateLimitFilter} (theo IP)
 * và {@link AuthenticatedRateLimitFilter} (theo user).
 *
 * <p><b>Fail-open khi Redis lỗi:</b> rate limit là lớp bảo vệ phụ, không được làm sập API.
 * Nếu lệnh Redis lỗi hoặc quá timeout, request được cho qua và service ngừng gọi Redis
 * trong {@code rate-limit.redis.cooldown-millis} (mặc định 5 giây) — giống một circuit
 * breaker đơn giản, tránh mọi request đều phải chờ timeout khi Redis đang sập. Cảnh báo
 * chỉ được log một lần cho mỗi đợt cooldown.
 *
 * <p>Ngoại lệ: caller có thể bật {@code localFallback} cho quota chống brute-force (login,
 * register, refresh, đăng nhập sai theo tài khoản). Khi Redis lỗi, các quota này chuyển
 * sang bucket trong bộ nhớ của từng instance thay vì cho qua toàn bộ.
 *
 * <p><b>Đổi cấu hình quota:</b> bucket đã có trong Redis giữ cấu hình cũ. Tăng
 * {@code rate-limit.config-version} để Bucket4j thay cấu hình của bucket cũ ở lần dùng kế
 * tiếp, giữ số token còn lại theo tỉ lệ ({@code PROPORTIONALLY}) — không reset quota
 * của mọi người về đầy cùng lúc.
 */
@Slf4j
@Component
public class RateLimitService {

    private final LettuceBasedProxyManager<byte[]> proxyManager;
    private final RateLimitMetrics metrics;
    private final long configVersion;
    private final long cooldownMillis;
    /** Nguồn thời gian (ms) — tách ra để test điều khiển được cooldown. */
    private final LongSupplier clock;
    /** Mốc thời gian (ms) tới khi nào còn bỏ qua Redis; 0 = Redis đang hoạt động bình thường. */
    private final AtomicLong unavailableUntil = new AtomicLong();
    /**
     * Bucket cục bộ cho {@code localFallback} khi Redis lỗi. LRU có giới hạn: trong lúc Redis sập,
     * request từ nhiều IP lạ không được làm phình bộ nhớ vô hạn (bucket cũ nhất bị loại).
     * Truy cập phải {@code synchronized} vì LinkedHashMap access-order không thread-safe.
     */
    private final Map<String, Bucket> localBuckets = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_LOCAL_BUCKETS;
        }
    };
    private static final int MAX_LOCAL_BUCKETS = 10_000;

    @Autowired
    public RateLimitService(LettuceBasedProxyManager<byte[]> proxyManager, RateLimitMetrics metrics,
                            @Value("${rate-limit.config-version:1}") long configVersion,
                            @Value("${rate-limit.redis.cooldown-millis:5000}") long cooldownMillis) {
        this(proxyManager, metrics, configVersion, cooldownMillis, System::currentTimeMillis);
    }

    /** Constructor cho test — nhận {@code clock} giả để kiểm tra cooldown mà không phải chờ thật. */
    RateLimitService(LettuceBasedProxyManager<byte[]> proxyManager, RateLimitMetrics metrics,
                     long configVersion, long cooldownMillis, LongSupplier clock) {
        Assert.isTrue(configVersion > 0, "rate-limit.config-version must be positive");
        Assert.isTrue(cooldownMillis > 0, "rate-limit.redis.cooldown-millis must be positive");
        this.proxyManager = proxyManager;
        this.metrics = metrics;
        this.configVersion = configVersion;
        this.cooldownMillis = cooldownMillis;
        this.clock = clock;
    }

    /**
     * Trừ 1 token của bucket {@code key}; bucket chưa tồn tại thì được tạo với {@code config}.
     *
     * @param key    key Redis của bucket, VD {@code rl:login:<ip>}, {@code rl:user:api:<userId>}
     * @param config capacity và chu kỳ nạp lại — chỉ dùng khi tạo bucket hoặc khi
     *               {@code configVersion} tăng
     * @param scope  tag metric: {@code ip}, {@code user} hoặc {@code account} (đăng nhập sai theo tài khoản)
     * @param policy tag metric: tên quota, VD {@code login}, {@code api}, {@code export}
     * @return kết quả trừ token; {@code null} khi bỏ qua kiểm tra (Redis lỗi hoặc đang
     *         cooldown) — caller phải coi {@code null} là cho phép request đi tiếp
     */
    public Decision check(String key, BucketConfiguration config, String scope, String policy) {
        return check(key, config, scope, policy, false);
    }

    /**
     * Như {@link #check(String, BucketConfiguration, String, String)}, nhưng nếu
     * {@code localFallback = true} thì khi Redis lỗi/cooldown vẫn giới hạn bằng bucket trong
     * bộ nhớ của instance này thay vì cho qua. Dùng cho quota chống brute-force (login,
     * register, refresh, số lần đăng nhập sai) — nơi fail-open để lộ lỗ hổng. Bucket cục bộ
     * không chia sẻ giữa các instance nên quota thực tế là {@code capacity × số instance}.
     *
     * @return {@code null} chỉ khi bỏ qua kiểm tra và {@code localFallback = false}
     */
    public Decision check(String key, BucketConfiguration config, String scope, String policy, boolean localFallback) {
        return run(key, config, scope, policy, localFallback, true);
    }

    /**
     * Xem bucket còn token không mà <b>không trừ</b> — dùng cho quota chỉ trừ khi thao tác thất
     * bại (VD đăng nhập sai: peek trước, sai mật khẩu mới gọi {@link #check}). Chỉ ghi metric
     * khi bị chặn để counter {@code allowed} không bị đếm hai lần.
     */
    public Decision peek(String key, BucketConfiguration config, String scope, String policy, boolean localFallback) {
        return run(key, config, scope, policy, localFallback, false);
    }

    private Decision run(String key, BucketConfiguration config, String scope, String policy,
                         boolean localFallback, boolean consume) {
        if (clock.getAsLong() < unavailableUntil.get()) {
            metrics.record(scope, policy, "bypassed");
            return localFallback ? local(key, config, scope, policy, consume) : null;
        }

        Decision decision;
        try {
            var bucket = proxyManager.builder()
                    .withImplicitConfigurationReplacement(configVersion, TokensInheritanceStrategy.PROPORTIONALLY)
                    .build(key.getBytes(StandardCharsets.UTF_8), () -> config)
                    .asVerbose();
            if (consume) {
                var result = bucket.tryConsumeAndReturnRemaining(1);
                decision = new Decision(result.getValue(), capacity(result.getConfiguration()));
            } else {
                var result = bucket.estimateAbilityToConsume(1);
                decision = new Decision(toProbe(result.getValue()), capacity(result.getConfiguration()));
            }
        } catch (Exception failure) {
            long now = clock.getAsLong();
            long previous = unavailableUntil.getAndAccumulate(now + cooldownMillis, Math::max);
            if (now >= previous) {
                log.warn("Rate limit Redis check failed; bypassing checks for {} ms", cooldownMillis, failure);
            }
            metrics.record(scope, policy, "error");
            return localFallback ? local(key, config, scope, policy, consume) : null;
        }

        if (consume || !decision.probe().isConsumed()) {
            metrics.record(scope, policy, decision.probe().isConsumed() ? "allowed" : "blocked");
        }
        return decision;
    }

    // Bucket trong bộ nhớ khi Redis không dùng được — chỉ cho policy bật localFallback
    private Decision local(String key, BucketConfiguration config, String scope, String policy, boolean consume) {
        Bucket bucket;
        synchronized (localBuckets) {
            bucket = localBuckets.computeIfAbsent(key, ignored -> newLocalBucket(config));
        }
        ConsumptionProbe probe = consume
                ? bucket.tryConsumeAndReturnRemaining(1)
                : toProbe(bucket.estimateAbilityToConsume(1));
        if (!probe.isConsumed()) {
            metrics.record(scope, policy, "fallback_blocked");
        }
        return new Decision(probe, capacity(config));
    }

    private static Bucket newLocalBucket(BucketConfiguration config) {
        var builder = Bucket.builder();
        for (Bandwidth bandwidth : config.getBandwidths()) {
            builder.addLimit(bandwidth);
        }
        return builder.build();
    }

    private static long capacity(BucketConfiguration config) {
        return config.getBandwidths()[0].getCapacity();
    }

    // estimateAbilityToConsume trả EstimationProbe — đổi sang ConsumptionProbe để caller dùng chung Decision
    private static ConsumptionProbe toProbe(EstimationProbe estimation) {
        return estimation.canBeConsumed()
                ? ConsumptionProbe.consumed(estimation.getRemainingTokens(), 0)
                : ConsumptionProbe.rejected(estimation.getRemainingTokens(),
                        estimation.getNanosToWaitForRefill(), estimation.getNanosToWaitForRefill());
    }

    /**
     * Kết quả một lần kiểm tra.
     *
     * @param probe {@code probe.isConsumed()} = còn quota (cho qua); nếu không, chứa số token
     *              còn lại và thời gian chờ nạp lại để dựng header 429
     * @param limit capacity của bucket — trả về qua header {@code RateLimit-Limit}
     */
    public record Decision(ConsumptionProbe probe, long limit) {
    }
}
