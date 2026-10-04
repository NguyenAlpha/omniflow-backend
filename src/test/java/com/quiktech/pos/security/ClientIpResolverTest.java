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

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
