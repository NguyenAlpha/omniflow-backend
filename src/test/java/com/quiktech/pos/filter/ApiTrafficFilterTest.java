package com.quiktech.pos.filter;

import com.quiktech.pos.service.ApiTrafficRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ApiTrafficFilterTest {

    private final ApiTrafficRecorder recorder = mock(ApiTrafficRecorder.class);
    private final ApiTrafficFilter filter = new ApiTrafficFilter(recorder);

    @Test
    void recordsRoutePatternStatusAndStoreOfMatchedRequest() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/stores/12/orders");

        filter.doFilter(request, new MockHttpServletResponse(),
                handledBy("/api/stores/{storeId}/orders", Map.of("storeId", "12"), 201));

        verify(recorder).record(eq("POST"), eq("/api/stores/{storeId}/orders"), eq(201), anyLong(), isNull(), eq(12L));
    }

    @Test
    void prefersBusinessIdWhenPresent() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/businesses/3/products");

        filter.doFilter(request, new MockHttpServletResponse(),
                handledBy("/api/businesses/{businessId}/products", Map.of("businessId", "3"), 200));

        verify(recorder).record(eq("GET"), eq("/api/businesses/{businessId}/products"), eq(200), anyLong(), eq(3L), isNull());
    }

    @Test
    void requestRejectedBeforeControllerIsUnmatched() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/stores/12/orders");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(401));

        verify(recorder).record(eq("GET"), eq("(unmatched)"), eq(401), anyLong(), isNull(), isNull());
    }

    @Test
    void adminRoutesAreNotAttributedToBusinesses() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/admin/subscriptions/5");

        filter.doFilter(request, new MockHttpServletResponse(),
                handledBy("/api/admin/subscriptions/{businessId}", Map.of("businessId", "5"), 200));

        verify(recorder).record(eq("GET"), eq("/api/admin/subscriptions/{businessId}"), eq(200), anyLong(), isNull(), isNull());
    }

    @Test
    void exceptionIsRecordedAsServerError() {
        var request = new MockHttpServletRequest("GET", "/api/users/me");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        verify(recorder).record(eq("GET"), eq("(unmatched)"), eq(500), anyLong(), isNull(), isNull());
    }

    @Test
    void ignoresNonApiPathsAndPreflight() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/swagger-ui.html"), new MockHttpServletResponse(), (req, res) -> {});
        filter.doFilter(new MockHttpServletRequest("OPTIONS", "/api/users/me"), new MockHttpServletResponse(), (req, res) -> {});

        verifyNoInteractions(recorder);
    }

    // Mô phỏng DispatcherServlet: gắn mẫu route + path variable vào request rồi controller trả status
    private static FilterChain handledBy(String pattern, Map<String, String> variables, int status) {
        return (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
            req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
            ((HttpServletResponse) res).setStatus(status);
        };
    }
}
