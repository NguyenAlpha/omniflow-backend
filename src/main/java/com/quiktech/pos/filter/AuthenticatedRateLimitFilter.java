package com.quiktech.pos.filter;

import com.quiktech.pos.security.UserPrincipal;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;

/**
 * Rate limit cho request API đã được Spring Security xác thực.
 *
 * <p>Filter này phải nằm sau {@code BearerTokenAuthenticationFilter}; khi đó
 * {@link UserPrincipal} đã có trong {@code SecurityContext} và quota được tính
 * theo user thay vì IP. Nhờ vậy nhiều nhân viên dùng chung mạng cửa hàng không
 * tự chặn nhau.
 *
 * <p>Không đánh rate limit request chưa xác thực tại đây. Chúng được xử lý bởi
 * {@link RateLimitFilter}, chạy ở lớp servlet trước Spring Security.
 *
 * <p><b>Đăng ký:</b> class không có {@code @Component}. {@code SecurityConfig} tạo bean, gắn
 * vào chuỗi filter của Spring Security bằng {@code addFilterAfter(..., BearerTokenAuthenticationFilter)}
 * và tắt đăng ký servlet tự động ({@code FilterRegistrationBean.setEnabled(false)}) — nếu không,
 * Spring Boot sẽ chạy thêm filter này một lần nữa trước khi JWT được xác thực.
 *
 * <p><b>Quota:</b> mỗi request khớp đúng một rule ({@link #resolveRule}); rule riêng thay thế
 * hoàn toàn quota chung chứ không cộng dồn:
 * <ul>
 *   <li>{@code import-products}: {@code POST /api/businesses/{id}/products/import} — 5 / 10 phút</li>
 *   <li>{@code export}: {@code GET}/{@code HEAD /api/stores/{id}/export/**} — 10 / 10 phút</li>
 *   <li>{@code inventory-bulk}: {@code POST /api/stores/{id}/inventory/adjust/bulk} và
 *       {@code /transfer/bulk}, chung một bucket — 10 / 10 phút</li>
 *   <li>{@code change-password}: {@code PATCH /api/users/me/password} — 5 / 10 phút</li>
 *   <li>{@code api}: mọi API còn lại — 300 / phút</li>
 * </ul>
 * Con số trên là mặc định, chỉnh qua {@code rate-limit.<rule>.user.*}. Bucket key:
 * {@code rl:user:<rule>:<userId>}.
 */
public class AuthenticatedRateLimitFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;
    private final RateLimitService rateLimitService;

    @Value("${rate-limit.api.user.max-requests:300}")
    private int maxRequests;

    @Value("${rate-limit.api.user.window-seconds:60}")
    private int windowSeconds;
    @Value("${rate-limit.import-products.user.max-requests:5}")
    private int importProductsMaxRequests;
    @Value("${rate-limit.import-products.user.window-seconds:600}")
    private int importProductsWindowSeconds;
    @Value("${rate-limit.export.user.max-requests:10}")
    private int exportMaxRequests;
    @Value("${rate-limit.export.user.window-seconds:600}")
    private int exportWindowSeconds;
    @Value("${rate-limit.inventory-bulk.user.max-requests:10}")
    private int inventoryBulkMaxRequests;
    @Value("${rate-limit.inventory-bulk.user.window-seconds:600}")
    private int inventoryBulkWindowSeconds;
    @Value("${rate-limit.change-password.user.max-requests:5}")
    private int changePasswordMaxRequests;
    @Value("${rate-limit.change-password.user.window-seconds:600}")
    private int changePasswordWindowSeconds;

    private BucketConfiguration apiConfig;
    private BucketConfiguration importProductsConfig;
    private BucketConfiguration exportConfig;
    private BucketConfiguration inventoryBulkConfig;
    private BucketConfiguration changePasswordConfig;

    public AuthenticatedRateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    /** Dựng cấu hình bucket cho từng rule một lần, sau khi các giá trị {@code @Value} đã được inject. */
    @PostConstruct
    void initBucketConfig() {
        apiConfig = bucketConfig(maxRequests, windowSeconds);
        importProductsConfig = bucketConfig(importProductsMaxRequests, importProductsWindowSeconds);
        exportConfig = bucketConfig(exportMaxRequests, exportWindowSeconds);
        inventoryBulkConfig = bucketConfig(inventoryBulkMaxRequests, inventoryBulkWindowSeconds);
        changePasswordConfig = bucketConfig(changePasswordMaxRequests, changePasswordWindowSeconds);
    }

    /** Bucket tối đa {@code capacity} request, nạp lại đủ {@code capacity} token một lượt sau mỗi {@code refillSeconds}. */
    private BucketConfiguration bucketConfig(int capacity, int refillSeconds) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillIntervally(capacity, Duration.ofSeconds(refillSeconds))
                        .build())
                .build();
    }

    /**
     * Bỏ qua preflight {@code OPTIONS}, path ngoài {@code /api/} và request chưa xác thực
     * (endpoint công khai như {@code /api/auth/login}, {@code GET /api/plans} chỉ chịu quota IP).
     * Còn lại: trừ 1 token của bucket theo rule + userId; hết quota thì trả 429 và dừng chain.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equals(request.getMethod()) || !PATH_HELPER.getPathWithinApplication(request).startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitRule rule = resolveRule(request);
        String bucketKey = "rl:user:" + rule.name() + ":" + principal.userId();
        RateLimitService.Decision decision = rateLimitService.check(bucketKey, rule.config(), "user", rule.name());
        if (decision != null && !decision.probe().isConsumed()) {
            RateLimitResponseWriter.write(response, decision.probe(), decision.limit());
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Chọn rule theo method + path đã decode (dùng {@link UrlPathHelper} để {@code %xx} không
     * né được rule). Thứ tự kiểm tra là thứ tự ưu tiên; không khớp rule riêng nào thì dùng
     * quota chung {@code api}.
     */
    private RateLimitRule resolveRule(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        String method = request.getMethod();

        if ("POST".equals(method) && path.matches("^/api/businesses/[^/]+/products/import$")) {
            return new RateLimitRule("import-products", importProductsConfig);
        }
        if (("GET".equals(method) || "HEAD".equals(method)) && path.matches("^/api/stores/[^/]+/export/.+$")) {
            return new RateLimitRule("export", exportConfig);
        }
        // adjust/bulk và transfer/bulk dùng chung bucket: mỗi request ghi tới 200 dòng tồn kho
        if ("POST".equals(method) && path.matches("^/api/stores/[^/]+/inventory/(adjust|transfer)/bulk$")) {
            return new RateLimitRule("inventory-bulk", inventoryBulkConfig);
        }
        if ("PATCH".equals(method) && "/api/users/me/password".equals(path)) {
            return new RateLimitRule("change-password", changePasswordConfig);
        }
        return new RateLimitRule("api", apiConfig);
    }

    /** {@code name} vừa là phần giữa của bucket key vừa là tag {@code policy} của metric. */
    private record RateLimitRule(String name, BucketConfiguration config) {
    }
}
