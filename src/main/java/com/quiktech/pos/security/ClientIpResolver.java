package com.quiktech.pos.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lấy IP client để dùng cho các quyết định bảo mật và audit log.
 *
 * <p>{@code X-Forwarded-For} là header do client có thể tự gửi. Header chỉ được
 * tin nếu socket kết nối trực tiếp đến backend ({@code remoteAddr}) là một proxy
 * đã được khai báo trong {@code rate-limit.trusted-proxies}. Với proxy đáng tin,
 * entry cuối là IP mà proxy vừa thêm vào chuỗi.
 *
 * <p>Khi có nhiều lớp proxy (VD CDN → Nginx → app), entry cuối lại là IP của proxy lớp
 * ngoài. Vì vậy chuỗi được duyệt từ phải sang trái, bỏ qua các entry là proxy tin cậy;
 * entry đầu tiên không tin cậy là client. Các entry bên trái nó do client tự gửi nên
 * không được dùng. {@code rate-limit.trusted-proxies} nhận cả IP lẻ lẫn dải CIDR
 * (VD {@code 10.0.0.0/8}).
 */
@Component
public class ClientIpResolver {

    // Chỉ đưa vào IpAddressMatcher chuỗi trông như IP literal — tên miền sẽ kích hoạt DNS lookup
    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private final List<IpAddressMatcher> trustedProxies;

    public ClientIpResolver(@Value("${rate-limit.trusted-proxies:}") Set<String> trustedProxies) {
        this.trustedProxies = trustedProxies.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }

        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrustedProxy(hop)) {
                return hop;
            }
        }
        // Mọi entry đều là proxy tin cậy → entry xa nhất là thông tin tốt nhất có được
        return hops[0].trim().isEmpty() ? remoteAddr : hops[0].trim();
    }

    private boolean isTrustedProxy(String address) {
        if (trustedProxies.isEmpty()) return false;
        if (!IPV4_LITERAL.matcher(address).matches() && address.indexOf(':') < 0) return false;
        try {
            return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
        } catch (IllegalArgumentException invalidAddress) {
            return false;
        }
    }
}
