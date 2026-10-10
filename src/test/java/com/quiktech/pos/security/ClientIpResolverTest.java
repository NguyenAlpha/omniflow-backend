package com.quiktech.pos.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void ignoresForwardedHeaderWhenDirectPeerIsNotTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.10"));
        MockHttpServletRequest request = request("198.51.100.8", "203.0.113.99");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.8");
    }

    @Test
    void usesLastForwardedHopWhenDirectPeerIsTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.10"));
        MockHttpServletRequest request = request("10.0.0.10", "spoofed-by-client, 203.0.113.99");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.99");
    }

    @Test
    void fallsBackToDirectPeerWhenTrustedProxyDidNotSendForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.10"));
        MockHttpServletRequest request = request("10.0.0.10", null);

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");
    }

    @Test
    void skipsTrustedProxyHopsWhenSeveralProxyLayersForward() {
        // CDN (203.0.113.10) → Nginx (10.0.0.10) → app: hop cuối là CDN, client đứng ngay trước nó
        ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.10", "203.0.113.0/24"));
        MockHttpServletRequest request = request("10.0.0.10", "spoofed-by-client, 198.51.100.7, 203.0.113.10");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void acceptsCidrRangesForTrustedProxies() {
        ClientIpResolver resolver = new ClientIpResolver(Set.of("10.0.0.0/8"));

        assertThat(resolver.resolve(request("10.20.30.40", "198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("11.0.0.1", "198.51.100.7"))).isEqualTo("11.0.0.1");
    }

    @Test
    void ignoresBlankTrustedProxyConfiguration() {
        ClientIpResolver resolver = new ClientIpResolver(Set.of(""));

        assertThat(resolver.resolve(request("198.51.100.8", "203.0.113.99"))).isEqualTo("198.51.100.8");
    }

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
