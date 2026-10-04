package com.quiktech.pos.filter;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality metrics for rate limit monitoring.
 *
 * <p>Never add user IDs, IPs, paths with IDs, or request headers as tags; that
 * would leak data and create unbounded Prometheus time series.
 */
@Component
@RequiredArgsConstructor
public class RateLimitMetrics {

    private final MeterRegistry meterRegistry;

    public void record(String scope, String policy, String outcome) {
        meterRegistry.counter("rate_limit_requests_total",
                "scope", scope,
                "policy", policy,
                "outcome", outcome)
                .increment();
    }
}
