package com.quiktech.pos.filter;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Slf4j
@Component
public class RateLimitService {

    private final LettuceBasedProxyManager<byte[]> proxyManager;
    private final RateLimitMetrics metrics;
    private final long configVersion;
    private final long cooldownMillis;
    private final LongSupplier clock;
    private final AtomicLong unavailableUntil = new AtomicLong();

    @Autowired
    public RateLimitService(LettuceBasedProxyManager<byte[]> proxyManager, RateLimitMetrics metrics,
                            @Value("${rate-limit.config-version:1}") long configVersion,
                            @Value("${rate-limit.redis.cooldown-millis:5000}") long cooldownMillis) {
        this(proxyManager, metrics, configVersion, cooldownMillis, System::currentTimeMillis);
    }

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

    public Decision check(String key, BucketConfiguration config, String scope, String policy) {
        if (clock.getAsLong() < unavailableUntil.get()) {
            metrics.record(scope, policy, "bypassed");
            return null;
        }

        Decision decision;
        try {
            var bucket = proxyManager.builder()
                    .withImplicitConfigurationReplacement(configVersion, TokensInheritanceStrategy.PROPORTIONALLY)
                    .build(key.getBytes(StandardCharsets.UTF_8), () -> config);
            var result = bucket.asVerbose().tryConsumeAndReturnRemaining(1);
            decision = new Decision(result.getValue(), result.getConfiguration().getBandwidths()[0].getCapacity());
        } catch (Exception failure) {
            long now = clock.getAsLong();
            long previous = unavailableUntil.getAndAccumulate(now + cooldownMillis, Math::max);
            if (now >= previous) {
                log.warn("Rate limit Redis check failed; bypassing checks for {} ms", cooldownMillis, failure);
            }
            metrics.record(scope, policy, "error");
            return null;
        }

        metrics.record(scope, policy, decision.probe().isConsumed() ? "allowed" : "blocked");
        return decision;
    }

    public record Decision(ConsumptionProbe probe, long limit) {
    }
}
