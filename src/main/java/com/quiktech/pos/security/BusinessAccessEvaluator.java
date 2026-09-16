package com.quiktech.pos.security;

import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.entity.enums.RoleName;
import com.quiktech.pos.repository.UserRoleRepository;
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

        if (resolveBusinessRoleWithCache(principal.userId(), businessId) != null) return true;

        return resolveBusinessMemberRoleWithCache(principal.userId(), businessId) != null;
    }

    /**
     * Kiểm tra user có quyền ghi vào catalog không — OWNER/BUSINESS_MANAGER (cấp business)
     * hoặc MANAGER của store trong business.
     */
    public boolean isOwnerOrManager(Long businessId, Authentication authentication) {
        if (authentication == null) return false;
        if (isSuperAdmin(authentication)) return true;
        UserPrincipal principal = extractPrincipal(authentication);
        if (principal == null) return false;

        RoleName businessRole = resolveBusinessRoleWithCache(principal.userId(), businessId);
        if (businessRole == RoleName.ROLE_OWNER || businessRole == RoleName.ROLE_BUSINESS_MANAGER) return true;

        return RoleName.ROLE_MANAGER.name().equals(
                resolveBusinessMemberRoleWithCache(principal.userId(), businessId));
    }

    /**
     * Kiểm tra user có phải OWNER của business không — CHỈ owner thật, KHÔNG gồm BUSINESS_MANAGER
     * (trợ lý bị loại khỏi các thao tác owner-only: subscription, hồ sơ business, quản lý trợ lý).
     */
    public boolean isOwner(Long businessId, Authentication authentication) {
        if (authentication == null) return false;
        if (isSuperAdmin(authentication)) return true;
        UserPrincipal principal = extractPrincipal(authentication);
        if (principal == null) return false;
        return resolveBusinessRoleWithCache(principal.userId(), businessId) == RoleName.ROLE_OWNER;
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
     * Resolve business-scoped role (OWNER hoặc BUSINESS_MANAGER) của user với Redis cache.
     * Cache lưu tên role thật tại {@code business:role:{userId}:{businessId}} — dùng chung với
     * {@link StoreAccessEvaluator}, nên phải ghi đúng tên role để phân biệt OWNER (được quản trị
     * business) vs BUSINESS_MANAGER (trợ lý, chỉ vận hành). Kết quả negative không cache.
     */
    private RoleName resolveBusinessRoleWithCache(Long userId, Long businessId) {
        String key = businessRoleCacheKey(userId, businessId);

        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return RoleName.valueOf(cached);
        } catch (Exception ignored) {
            // Redis down — tiếp tục xuống DB
        }

        RoleName role = userRoleRepository.findActiveBusinessRole(userId, businessId)
                .map(ur -> ur.getRole().getName())
                .orElse(null);

        if (role != null) {
            try {
                redisTemplate.opsForValue().set(key, role.name(), cacheTtlSeconds, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Redis down — bỏ qua
            }
        }

        return role;
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
