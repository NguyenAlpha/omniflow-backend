package com.quiktech.pos.filter;

import com.quiktech.pos.config.ApplicationConfig;
import com.quiktech.pos.config.RateLimiterConfig;
import com.quiktech.pos.config.SecurityConfig;
import com.quiktech.pos.controller.ExportController;
import com.quiktech.pos.repository.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.quiktech.pos.security.ClientIpResolver;
import com.quiktech.pos.security.StoreAccessEvaluator;
import com.quiktech.pos.service.ExportService;
import com.quiktech.pos.support.InMemoryRateLimitRedis;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebAppConfiguration
@SpringJUnitConfig(classes = RateLimitIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "cors.allowed-origins=http://localhost:3000",
        "rate-limit.login.max-requests=1",
        "rate-limit.api.ip.max-requests=10",
        "rate-limit.api.user.max-requests=2",
        "rate-limit.export.user.max-requests=1",
        "rate-limit.inventory-bulk.user.max-requests=1"
})
class RateLimitIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private FilterRegistrationBean<CorsFilter> corsRegistration;
    @Autowired private FilterRegistrationBean<AuthenticatedRateLimitFilter> userRegistration;
    @Autowired private RateLimitFilter ipFilter;
    @Autowired private InMemoryRateLimitRedis redis;
    @Autowired private ExportService exportService;
    @Autowired private JwtEncoder encoder;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        redis.clear();
        clearInvocations(exportService);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(corsRegistration.getFilter(), ipFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void registersCorsBeforeIpLimiterAndUserLimiterOnlyInsideSecurity() {
        assertThat(corsRegistration.getOrder()).isLessThan(RateLimitFilter.class.getAnnotation(Order.class).value());
        assertThat(userRegistration.isEnabled()).isFalse();
    }

    @Test
    void ipRejectionIncludesCorsAndReadableRateLimitHeaders() throws Exception {
        mvc.perform(post("/api/auth/login").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").header("Origin", "http://localhost:3000"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                .andExpect(header().string("RateLimit-Limit", "1"))
                .andExpect(header().string("RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void preflightDoesNotConsumeQuotaAndUntrustedOriginsAreRejected() throws Exception {
        int reads = redis.reads();
        mvc.perform(options("/api/users/me")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        assertThat(redis.reads()).isEqualTo(reads);
    }

    @Test
    void headCannotBypassExhaustedExportQuota() throws Exception {
        String bearer = bearer(42);
        mvc.perform(get("/api/stores/1/export/inventory").header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(head("/api/stores/1/export/inventory").header("Authorization", bearer)
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("RateLimit-Limit", "1"));
        mvc.perform(get("/api/stores/2/export/orders").header("Authorization", bearer))
                .andExpect(status().isTooManyRequests());
        verify(exportService).exportInventoryExcel(1L);
        verifyNoMoreInteractions(exportService);
    }

    @Test
    void successfulHeadConsumesExportQuota() throws Exception {
        String bearer = bearer(42);
        mvc.perform(head("/api/stores/1/export/inventory").header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(get("/api/stores/1/export/inventory").header("Authorization", bearer))
                .andExpect(status().isTooManyRequests());
        verify(exportService).exportInventoryExcel(1L);
        verifyNoMoreInteractions(exportService);
    }

    @Test
    void jwtIdentifiesUsersAndQuotaIsConsumedOncePerRequest() throws Exception {
        String firstUser = bearer(42);
        mvc.perform(get("/api/users/me").header("Authorization", firstUser)).andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", firstUser)).andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", firstUser)
                        .header("Origin", "http://localhost:3000"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("RateLimit-Limit", "2"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(get("/api/users/me").header("Authorization", bearer(43))).andExpect(status().isOk());
    }

    @Test
    void invalidJwtCannotReachExportAndStillConsumesIpQuota() throws Exception {
        for (int request = 0; request < 10; request++) {
            mvc.perform(get("/api/stores/1/export/inventory").header("Authorization", "Bearer invalid"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/stores/1/export/inventory").header("Authorization", "Bearer invalid"))
                .andExpect(status().isTooManyRequests());
        verifyNoInteractions(exportService);
    }

    @Test
    void bulkAdjustAndBulkTransferShareOneQuotaSeparateFromGeneralApi() throws Exception {
        String bearer = bearer(42);
        mvc.perform(post("/api/stores/1/inventory/adjust/bulk").header("Authorization", bearer))
                .andExpect(status().isOk());
        mvc.perform(post("/api/stores/1/inventory/transfer/bulk").header("Authorization", bearer))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("RateLimit-Limit", "1"));
        // Quota chung "api" (2 request) không bị trừ bởi request bulk
        mvc.perform(get("/api/users/me").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", bearer)).andExpect(status().isOk());
    }

    private String bearer(long userId) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject("review-user-" + userId)
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("userId", userId).claim("roles", List.of()).build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Configuration
    @EnableWebMvc
    @Import({ApplicationConfig.class, SecurityConfig.class, RateLimitFilter.class,
            RateLimitService.class, RateLimitMetrics.class, ClientIpResolver.class,
            ExportController.class, TestController.class})
    static class TestConfig {
        @Bean
        InMemoryRateLimitRedis redis() { return new InMemoryRateLimitRedis(); }

        @Bean
        LettuceBasedProxyManager<byte[]> rateLimitProxyManager(InMemoryRateLimitRedis redis) {
            return new RateLimiterConfig().rateLimitProxyManager(redis.connection(), 500);
        }

        @Bean
        MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }

        @Bean
        UserRepository userRepository() { return mock(UserRepository.class); }

        // SecurityConfig cần IdempotencyFilter; test này không gửi Idempotency-Key nên Redis không được gọi
        @Bean
        StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }

        @Bean
        ExportService exportService() {
            var service = mock(ExportService.class);
            when(service.exportInventoryExcel(anyLong())).thenReturn(new byte[]{1, 2, 3});
            return service;
        }

        @Bean
        StoreAccessEvaluator storeAccess() {
            var evaluator = mock(StoreAccessEvaluator.class);
            when(evaluator.isOwnerOrManager(anyLong(), any())).thenReturn(true);
            return evaluator;
        }
    }

    @RestController
    static class TestController {
        @PostMapping("/api/auth/login")
        Map<String, Boolean> login() { return Map.of("success", true); }

        @GetMapping("/api/users/me")
        Map<String, Boolean> me() { return Map.of("success", true); }

        @PostMapping({"/api/stores/{storeId}/inventory/adjust/bulk", "/api/stores/{storeId}/inventory/transfer/bulk"})
        Map<String, Boolean> inventoryBulk() { return Map.of("success", true); }
    }
}
