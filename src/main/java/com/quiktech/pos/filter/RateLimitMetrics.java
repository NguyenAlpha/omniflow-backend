package com.quiktech.pos.filter;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Metric giám sát rate limit, với số lượng tag ít (low-cardinality).
 *
 * <p>Ghi counter {@code rate_limit_requests_total} với 3 tag:
 * <ul>
 *   <li>{@code scope}: {@code ip} hoặc {@code user}</li>
 *   <li>{@code policy}: tên quota, VD {@code login}, {@code api}, {@code export}</li>
 *   <li>{@code outcome}: {@code allowed} (còn quota), {@code blocked} (trả 429),
 *       {@code error} (lệnh Redis lỗi), {@code bypassed} (bỏ qua vì đang cooldown sau lỗi Redis)</li>
 * </ul>
 * {@code error}/{@code bypassed} tăng nghĩa là rate limit đang tạm tắt (fail-open) — nên đặt cảnh báo.
 *
 * <p>Tuyệt đối không thêm user ID, IP, path chứa ID hay header của request làm tag: vừa lộ
 * dữ liệu, vừa tạo ra số time series Prometheus không giới hạn.
 */
@Component
@RequiredArgsConstructor
public class RateLimitMetrics {

    private final MeterRegistry meterRegistry;

    /** Tăng counter 1 đơn vị cho tổ hợp tag; Micrometer tự tạo counter ở lần gọi đầu. */
    public void record(String scope, String policy, String outcome) {
        meterRegistry.counter("rate_limit_requests_total",
                "scope", scope,
                "policy", policy,
                "outcome", outcome)
                .increment();
    }
}
