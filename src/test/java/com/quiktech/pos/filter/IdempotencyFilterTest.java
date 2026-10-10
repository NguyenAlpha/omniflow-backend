package com.quiktech.pos.filter;

import com.quiktech.pos.security.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdempotencyFilterTest {

    private static final String KEY = "idem:42:7:abc";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final IdempotencyFilter filter = new IdempotencyFilter(redis);
    private final AtomicInteger handled = new AtomicInteger();

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        authenticate(42L);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void storesSuccessfulResponseWithStatusAndReplaysIt() throws Exception {
        when(values.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class))).thenReturn(true);
        MockHttpServletResponse first = run(chain(201, "{\"id\":1}"));

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getContentAsString()).isEqualTo("{\"id\":1}");
        verify(values).set(KEY, "201:{\"id\":1}", Duration.ofHours(24));

        when(values.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class))).thenReturn(false);
        when(values.get(KEY)).thenReturn("201:{\"id\":1}");
        MockHttpServletResponse replay = run(chain(201, "{\"id\":2}"));

        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getContentAsString()).isEqualTo("{\"id\":1}");
        assertThat(replay.getHeader("X-Idempotency-Cached")).isEqualTo("true");
        assertThat(handled.get()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateWhileFirstRequestIsStillProcessing() throws Exception {
        when(values.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class))).thenReturn(false);
        when(values.get(KEY)).thenReturn("PENDING");

        MockHttpServletResponse response = run(chain(201, "{}"));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_CONFLICT);
        assertThat(response.getContentAsString()).contains("IDEMPOTENCY_REQUEST_IN_PROGRESS");
        assertThat(handled.get()).isZero();
    }

    @Test
    void releasesKeyWhenRequestFailsSoClientCanRetry() throws Exception {
        when(values.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class))).thenReturn(true);

        run(chain(400, "{\"success\":false}"));

        verify(redis).delete(KEY);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void scopesKeyByUserAndStore() throws Exception {
        authenticate(99L);
        when(values.setIfAbsent(eq("idem:99:7:abc"), eq("PENDING"), any(Duration.class))).thenReturn(true);

        run(chain(201, "{}"));

        verify(values).setIfAbsent(eq("idem:99:7:abc"), eq("PENDING"), any(Duration.class));
    }

    @Test
    void skipsUnauthenticatedRequestsWithoutTouchingRedis() throws Exception {
        SecurityContextHolder.clearContext();

        run(chain(401, "{}"));

        verifyNoInteractions(redis);
        assertThat(handled.get()).isEqualTo(1);
    }

    @Test
    void processesRequestNormallyWhenRedisIsDown() throws Exception {
        when(values.setIfAbsent(eq(KEY), eq("PENDING"), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        MockHttpServletResponse response = run(chain(201, "{\"id\":1}"));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(handled.get()).isEqualTo(1);
    }

    private MockHttpServletResponse run(FilterChain chain) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/stores/7/orders");
        request.addHeader("Idempotency-Key", "abc");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private FilterChain chain(int status, String body) {
        return (request, response) -> {
            handled.incrementAndGet();
            var http = (HttpServletResponse) response;
            http.setStatus(status);
            http.getWriter().write(body);
        };
    }

    private void authenticate(long userId) {
        var principal = new UserPrincipal(userId, "user" + userId, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
