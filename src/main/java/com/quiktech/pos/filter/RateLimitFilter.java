package com.quiktech.pos.filter;

import com.quiktech.pos.security.ClientIpResolver;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;

/**
 * Rate limit theo IP, chạy ở lớp servlet <b>trước</b> Spring Security (ngay sau CORS filter,
 * vốn có order {@code HIGHEST_PRECEDENCE}).
 *
 * <p>Chạy trước xác thực nên chặn được cả request chưa có JWT — brute-force đăng nhập,
 * spam đăng ký, flood — trước khi tốn công validate token hay chạm DB. Quota:
 * <ul>
 *   <li>{@code POST /api/auth/login}, {@code /register}, {@code /refresh}: quota riêng, chặt
 *       (bucket {@code rl:login|register|refresh:<ip>}).</li>
 *   <li>Mọi {@code /api/**} khác: trần chung rộng (mặc định 1.200/phút, bucket
 *       {@code rl:ip:api:<ip>}) — đủ cho nhiều máy cùng mạng một cửa hàng. Request đã xác
 *       thực còn qua thêm quota theo user ở {@link AuthenticatedRateLimitFilter}.</li>
 * </ul>
 *
 * <p>IP lấy qua {@link ClientIpResolver}: chỉ tin {@code X-Forwarded-For} khi request đến từ
 * proxy nằm trong {@code rate-limit.trusted-proxies}, tránh client tự giả IP để né quota.
 * Preflight {@code OPTIONS} của CORS không bị tính. Vượt quota → 429 qua
 * {@link RateLimitResponseWriter}; Redis lỗi → cho qua (xem {@link RateLimitService}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    @Value("${rate-limit.login.max-requests:10}")
    private int loginMaxRequests;
    @Value("${rate-limit.login.window-seconds:60}")
    private int loginWindowSeconds;
    @Value("${rate-limit.register.max-requests:5}")
    private int registerMaxRequests;
    @Value("${rate-limit.register.window-seconds:60}")
    private int registerWindowSeconds;
    @Value("${rate-limit.refresh.max-requests:20}")
    private int refreshMaxRequests;
    @Value("${rate-limit.refresh.window-seconds:60}")
    private int refreshWindowSeconds;
    @Value("${rate-limit.api.ip.max-requests:1200}")
    private int apiIpMaxRequests;
    @Value("${rate-limit.api.ip.window-seconds:60}")
    private int apiIpWindowSeconds;

    /**
     * Decode path trước khi so khớp — so sánh URI thô bằng endsWith có thể bị bypass
     * bằng URL-encoding (VD: /api/auth/logi%6E vẫn route tới /api/auth/login nhưng
     * không match chuỗi thô). UrlPathHelper trả về path đã decode, đã bỏ context path.
     */
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private BucketConfiguration loginConfig;
    private BucketConfiguration registerConfig;
    private BucketConfiguration refreshConfig;
    private BucketConfiguration apiIpConfig;

    /**
     * Dựng cấu hình bucket một lần sau khi Spring inject các giá trị {@code @Value}.
     * Mỗi quota là bucket có {@code capacity = max-requests}, nạp lại đủ {@code max-requests}
     * token một lượt sau mỗi {@code window-seconds} ({@code refillIntervally}) — tương đương
     * "tối đa N request mỗi cửa sổ thời gian".
     */
    @PostConstruct
    void initBucketConfigs() {
        loginConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(loginMaxRequests)
                        .refillIntervally(loginMaxRequests, Duration.ofSeconds(loginWindowSeconds))
                        .build())
                .build();

        registerConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(registerMaxRequests)
                        .refillIntervally(registerMaxRequests, Duration.ofSeconds(registerWindowSeconds))
                        .build())
                .build();

        refreshConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(refreshMaxRequests)
                        .refillIntervally(refreshMaxRequests, Duration.ofSeconds(refreshWindowSeconds))
                        .build())
                .build();

        apiIpConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(apiIpMaxRequests)
                        .refillIntervally(apiIpMaxRequests, Duration.ofSeconds(apiIpWindowSeconds))
                        .build())
                .build();
    }

    /**
     * Chọn quota theo method + path, trừ 1 token; hết quota thì trả 429 và dừng chain.
     * Path không thuộc {@code /api/} (VD actuator, swagger) không bị giới hạn ở đây.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if ("OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // Path đã decode + bỏ context path — so khớp bằng equals, không dùng endsWith
        // trên URI thô (bypass được bằng URL-encoding)
        String path = PATH_HELPER.getPathWithinApplication(request);
        String ip = bucketIp(clientIpResolver.resolve(request));
        String bucketKey = null;
        BucketConfiguration config = null;
        String policy = null;

        if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(path)) {
            bucketKey = "rl:login:" + ip;
            config = loginConfig;
            policy = "login";
        } else if ("POST".equals(request.getMethod()) && "/api/auth/register".equals(path)) {
            bucketKey = "rl:register:" + ip;
            config = registerConfig;
            policy = "register";
        } else if ("POST".equals(request.getMethod()) && "/api/auth/refresh".equals(path)) {
            bucketKey = "rl:refresh:" + ip;
            config = refreshConfig;
            policy = "refresh";
        } else if (path.startsWith("/api/")) {
            // Coarse per-IP ceiling for every application API request. Authenticated
            // traffic receives a second, fairer per-user limit after JWT validation.
            bucketKey = "rl:ip:api:" + ip;
            config = apiIpConfig;
            policy = "api";
        }

        if (bucketKey != null) {
            RateLimitService.Decision decision = rateLimitService.check(bucketKey, config, "ip", policy);
            if (decision != null && !decision.probe().isConsumed()) {
                RateLimitResponseWriter.write(response, decision.probe(), decision.limit());
                return;
            }
        }

        chain.doFilter(request, response);
    }

    /**
     * IP dùng trong bucket key. IPv6 được gom theo dải /64: nhà mạng thường cấp nguyên một
     * dải /64 cho mỗi thuê bao, nên tính theo từng địa chỉ thì client chỉ cần đổi địa chỉ
     * trong dải là có bucket mới, né được quota. IPv4 (kể cả IPv4-mapped {@code ::ffff:a.b.c.d})
     * giữ nguyên. Chuỗi không parse được thì dùng nguyên văn.
     */
    static String bucketIp(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            // Chuỗi chứa ':' được parse như IPv6 literal, không tra DNS
            InetAddress address = InetAddress.getByName(ip);
            if (address instanceof Inet4Address) {
                return address.getHostAddress();
            }
            byte[] prefix = Arrays.copyOf(address.getAddress(), 16);
            Arrays.fill(prefix, 8, 16, (byte) 0);
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (UnknownHostException invalid) {
            return ip;
        }
    }

}
