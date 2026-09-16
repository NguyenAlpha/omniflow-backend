package com.quiktech.pos.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quiktech.pos.annotation.Auditable;
import com.quiktech.pos.security.UserPrincipal;
import com.quiktech.pos.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.UUID;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @AfterReturning("@annotation(auditable)")
    public void audit(JoinPoint joinPoint, Auditable auditable) {
        try {
            Long userId = extractUserId();
            String ip = extractIp();

            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            String[] paramNames = sig.getParameterNames();
            Object[] args = joinPoint.getArgs();

            Long storeId = null;
            Long businessId = null;
            Object requestDto = null;

            for (int i = 0; i < paramNames.length; i++) {
                Object arg = args[i];
                if (arg == null) continue;

                switch (paramNames[i]) {
                    case "storeId"    -> storeId    = (Long) arg;
                    case "businessId" -> businessId = (Long) arg;
                    default -> {
                        // First non-primitive arg that isn't UserPrincipal, UUID, or primitive wrapper
                        if (requestDto == null
                                && !(arg instanceof Long)
                                && !(arg instanceof UUID)
                                && !(arg instanceof BigDecimal)
                                && !(arg instanceof Boolean)
                                && !(arg instanceof String)
                                && !(arg instanceof Enum<?>)
                                && !(arg instanceof UserPrincipal)) {
                            requestDto = arg;
                        }
                    }
                }
            }

            String newValue = null;
            if (requestDto != null) {
                try {
                    newValue = objectMapper.writeValueAsString(requestDto);
                } catch (Exception e) {
                    newValue = "{\"error\":\"serialization failed\"}";
                }
            }

            auditService.log(userId, businessId, storeId,
                    auditable.action(), auditable.entityType(),
                    null, null, newValue, ip);

        } catch (Exception e) {
            log.warn("AuditAspect failed silently: {}", e.getMessage());
        }
    }

    private Long extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p.userId();
        }
        return null;
    }

    private String extractIp() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                String forwarded = req.getHeader("X-Forwarded-For");
                return forwarded != null && !forwarded.isBlank()
                        ? forwarded.split(",")[0].trim()
                        : req.getRemoteAddr();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
