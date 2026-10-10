package com.quiktech.pos.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void groupsIpv6AddressesOfTheSameSlash64() {
        assertThat(RateLimitFilter.bucketIp("2001:db8:1:2:aaaa::1"))
                .isEqualTo(RateLimitFilter.bucketIp("2001:db8:1:2:bbbb:cccc:dddd:eeee"))
                .isEqualTo("2001:db8:1:2:0:0:0:0/64");
        assertThat(RateLimitFilter.bucketIp("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3:0:0:0:0/64");
    }

    @Test
    void keepsIpv4AndUnwrapsIpv4MappedIpv6() {
        assertThat(RateLimitFilter.bucketIp("198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(RateLimitFilter.bucketIp("::ffff:198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void leavesUnparseableValuesUnchanged() {
        assertThat(RateLimitFilter.bucketIp("not:an:ip:zz")).isEqualTo("not:an:ip:zz");
    }
}
