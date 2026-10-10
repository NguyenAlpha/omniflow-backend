package com.quiktech.pos.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ApiTrafficServiceTest {

    @Test
    void percentileReturnsUpperBoundOfTheBucketHoldingIt() {
        // 90 request ≤ 50 ms, 9 request ∈ (250, 500], 1 request > 5000 ms
        long[] buckets = {90, 0, 0, 9, 0, 0, 0, 1};

        assertThat(ApiTrafficService.percentile(buckets, 0.50)).isEqualTo(50L);
        assertThat(ApiTrafficService.percentile(buckets, 0.95)).isEqualTo(500L);
        assertThat(ApiTrafficService.percentile(buckets, 0.999)).isNull();   // rơi vào khoảng > 5000 ms
        assertThat(ApiTrafficService.percentile(new long[8], 0.95)).isNull(); // không có request
    }

    @Test
    void rejectsUnknownRange() {
        @SuppressWarnings("unchecked")
        var service = new ApiTrafficService(mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class),
                mock(io.micrometer.core.instrument.MeterRegistry.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));

        assertThatThrownBy(() -> service.report("5m")).isInstanceOf(IllegalArgumentException.class);
    }
}
