package com.quiktech.pos.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Lấy IP client để dùng cho các quyết định bảo mật và audit log.
 *
 * <p>{@code X-Forwarded-For} là header do client có thể tự gửi. Header chỉ được
 * tin nếu socket kết nối trực tiếp đến backend ({@code remoteAddr}) là một proxy
 * đã được khai báo trong {@code rate-limit.trusted-proxies}. Với proxy đáng tin,
 * entry cuối là IP mà proxy vừa thêm vào chuỗi.
 */
@Component
public class ClientIpResolver {

    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${rate-limit.trusted-proxies:}") Set<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!trustedProxies.contains(remoteAddr)) {
            return remoteAddr;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }

        String[] hops = forwarded.split(",");
        return hops[hops.length - 1].trim();
    }
}
