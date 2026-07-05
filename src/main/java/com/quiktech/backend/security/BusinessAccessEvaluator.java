package com.quiktech.backend.security;

import com.quiktech.backend.entity.UserRole;
import com.quiktech.backend.entity.enums.RoleName;
import com.quiktech.backend.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Kiểm tra quyền truy cập theo business context — dùng trong {@code @PreAuthorize} cho catalog endpoints.
 *
 * <p>Catalog data (product, category, unit, customer, supplier) thuộc business, không phải store.
 * OWNER có role business-level; MANAGER/STAFF có role store-level nhưng vẫn cần đọc catalog.
 *
 * <p>OWNER role được cache tại {@code business:role:{userId}:{businessId}}.
 * Store membership (MANAGER/STAFF) được cache tại {@code business:member:{userId}:{businessId}}.
 * TTL dùng chung {@code store.role.cache.ttl}. Gọi {@link #evictBusinessRoleCache} và
 * {@link #evictBusinessMemberCache} sau khi thay đổi membership.
 */
@Component("businessAccess")
@RequiredArgsConstructor
public class BusinessAccessEvaluator {

    private final UserRoleRepository userRoleRepository;
    private final StringRedisTemplate redisTemplate;

    @Value("${store.role.cache.ttl:300}")
    private long cacheTtlSeconds;

    /**
     * Kiểm tra user có thuộc business không — OWNER hoặc MANAGER/STAFF của bất kỳ store trong business.
     */
    public boolean isMember(Long businessId, Authentication authentication) {
        if (authentication == null) return false;
        if (isSuperAdmin(authentication)) return true;
        UserPrincipal principal = extractPrincipal(authentication);
        if (principal == null) return false;

        if (isOwnerWithCache(principal.userId(), businessId)) return true;

        return resolveBusinessMemberRoleWithCache(principal.userId(), businessId) != null;
    }

    /**
     * Kiểm tra user có quyền ghi vào catalog không — OWNER hoặc MANAGER của store trong business.
     */
    public boolean isOwnerOrManager(Long businessId, Authentication authentication) {
        if (authentication == null) return false;
        if (isSuperAdmin(authentication)) return true;
        UserPrincipal principal = extractPrincipal(authentication);
        if (principal == null) return false;

        if (isOwnerWithCache(principal.userId(), businessId)) return true;

        return RoleName.ROLE_MANAGER.name().equals(
                resolveBusinessMemberRoleWithCache(principal.userId(), businessId));
    }

    /**
     * Kiểm tra user có phải OWNER của business không.
     */
    public boolean isOwner(Long businessId, Authentication authentication) {
        if (authentication == null) return false;
        if (isSuperAdmin(authentication)) return true;
        UserPrincipal principal = extractPrincipal(authentication);
        if (principal == null) return false;
        return isOwnerWithCache(principal.userId(), businessId);
    }

    /**
     * Xóa cache OWNER role của user trong business. Phải gọi sau khi transaction thay đổi
     * business membership đã commit.
     */
    public void evictBusinessRoleCache(Long userId, Long businessId) {
        try {
            redisTemplate.delete(businessRoleCacheKey(userId, businessId));
        } catch (Exception ignored) {
            // Redis down — cache sẽ expire tự nhiên sau TTL
        }
    }

    /**
     * Xóa cache store membership của user trong business. Phải gọi sau khi transaction thay đổi
     * store membership (add/update/remove store member) đã commit.
     */
    public void evictBusinessMemberCache(Long userId, Long businessId) {
        try {
            redisTemplate.delete(businessMemberCacheKey(userId, businessId));
        } catch (Exception ignored) {
            // Redis down — cache sẽ expire tự nhiên sau TTL
        }
    }

    /**
     * Kiểm tra OWNER với Redis cache. Cache chỉ lưu kết quả positive (có OWNER role).
     * Kết quả negative không cache — DB call mỗi request, nhưng non-owner hiếm khi gọi
     * catalog endpoint nên không thành vấn đề.
     */
    private boolean isOwnerWithCache(Long userId, Long businessId) {
        String key = businessRoleCacheKey(userId, businessId);

        try {
            if (redisTemplate.opsForValue().get(key) != null) return true;
        } catch (Exception ignored) {
            // Redis down — tiếp tục xuống DB
        }

        boolean isOwner = userRoleRepository.findActiveBusinessRole(userId, businessId).isPresent();

        if (isOwner) {
            try {
                redisTemplate.opsForValue().set(key, RoleName.ROLE_OWNER.name(), cacheTtlSeconds, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Redis down — bỏ qua
            }
        }

        return isOwner;
    }

    /**
     * Lấy role cao nhất của user trong business qua store membership: ưu tiên Redis, fallback về DB.
     * Cache {@code "ROLE_MANAGER"} nếu có ít nhất 1 store MANAGER trong business,
     * {@code "ROLE_STAFF"} nếu chỉ có STAFF. Không cache nếu không có role nào.
     */
    private String resolveBusinessMemberRoleWithCache(Long userId, Long businessId) {
        String key = businessMemberCacheKey(userId, businessId);

        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return cached;
        } catch (Exception ignored) {
            // Redis down — tiếp tục xuống DB
        }

        List<UserRole> roles = userRoleRepository.findActiveStoreRolesInBusiness(userId, businessId);
        if (roles.isEmpty()) return null;

        String highest = roles.stream().anyMatch(ur -> RoleName.ROLE_MANAGER == ur.getRole().getName())
                ? RoleName.ROLE_MANAGER.name()
                : RoleName.ROLE_STAFF.name();

        try {
            redisTemplate.opsForValue().set(key, highest, cacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Redis down — bỏ qua
        }

        return highest;
    }

    private static boolean isSuperAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> RoleName.ROLE_SUPER_ADMIN.name().equals(a.getAuthority()));
    }

    private static UserPrincipal extractPrincipal(Authentication authentication) {
        return authentication.getPrincipal() instanceof UserPrincipal p ? p : null;
    }

    /**
     * Redis key cho business-level OWNER role: {@code business:role:{userId}:{businessId}}.
     */
    private static String businessRoleCacheKey(Long userId, Long businessId) {
        return "business:role:" + userId + ":" + businessId;
    }

    /**
     * Redis key cho business store membership: {@code business:member:{userId}:{businessId}}.
     */
    private static String businessMemberCacheKey(Long userId, Long businessId) {
        return "business:member:" + userId + ":" + businessId;
    }
}
