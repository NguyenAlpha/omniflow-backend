package com.quiktech.pos.filter;

import com.quiktech.pos.service.ApiTrafficRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Map;

/**
 * Đo mọi request {@code /api/**} cho dashboard lưu lượng của admin ({@link ApiTrafficRecorder}).
 *
 * <p>Chạy ngoài cùng ({@code HIGHEST_PRECEDENCE}, trước rate limit và Spring Security) để đếm
 * được cả request bị chặn 429/401. Route ghi nhận là <b>mẫu route</b> Spring đặt vào request
 * sau khi khớp controller (VD {@code /api/stores/{storeId}/orders}); request không tới controller
 * thì là {@code (unmatched)} — không dùng URL thật để số dòng thống kê không tăng theo số ID.
 *
 * <p>Business được lấy từ path variable {@code businessId}/{@code storeId}; route admin
 * ({@code /api/admin/**}) không được tính cho business vì đó là admin thao tác, không phải
 * business dùng hệ thống. Preflight {@code OPTIONS} không được đếm.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ApiTrafficFilter extends OncePerRequestFilter {

    static final String UNMATCHED = "(unmatched)";
    private static final int MAX_ROUTE_LENGTH = 200;
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private final ApiTrafficRecorder recorder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) || !PATH_HELPER.getPathWithinApplication(request).startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            // Exception lọt ra ngoài thì container sẽ trả 500 dù status hiện tại chưa được set
            int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
            String route = route(request);
            Map<?, ?> variables = (Map<?, ?>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            boolean adminRoute = route.startsWith("/api/admin/");
            recorder.record(request.getMethod(), route, status, durationMs,
                    adminRoute ? null : id(variables, "businessId"),
                    adminRoute ? null : id(variables, "storeId"));
        }
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String route) || route.isBlank()) return UNMATCHED;
        return route.length() > MAX_ROUTE_LENGTH ? route.substring(0, MAX_ROUTE_LENGTH) : route;
    }

    private static Long id(Map<?, ?> variables, String name) {
        if (variables == null || !(variables.get(name) instanceof String value)) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
