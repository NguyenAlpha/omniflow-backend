package com.quiktech.pos.security;

import com.quiktech.pos.config.SecurityConfig;
import com.quiktech.pos.entity.enums.RoleName;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import com.quiktech.pos.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Kiểm tra quyền truy cập theo store context — dùng trong {@code @PreAuthorize}.
 *
 * <h3>Vì sao cần class này</h3>
 * Spring Security chỉ lưu global role ({@code SUPER_ADMIN}, {@code SUPPORT}) trong
 * {@code SecurityContext} thông qua JWT claims. Store-scoped role ({@code MANAGER}, {@code STAFF})
 * và business-scoped role ({@code OWNER}) phụ thuộc vào context nên không thể nhúng vào JWT.
 *
 * <h3>Luồng kiểm tra quyền (isMember / isOwnerOrManager)</h3>
 * <pre>
 * 1. SUPER_ADMIN bypass → true
 * 2. Kiểm tra business OWNER role:
 *    - Resolve businessId từ storeId (cached tại store:business:{storeId})
 *    - isOwnerWithCache(userId, businessId) — cached tại business:role:{userId}:{businessId}
 * 3. Kiểm tra store-level role (MANAGER/STAFF) qua Redis cache + DB
 * </pre>
 *
 * <h3>Redis down — Graceful degradation</h3>
 * Mọi thao tác Redis đều được bọc try-catch. Khi Redis không available:
 * <ul>
 *   <li>Read fail → fallback về DB</li>
 *   <li>Write fail → bỏ qua</li>
 *   <li>Evict fail → entry cũ tự expire sau TTL</li>
 * </ul>
 *
 * <h3>Cache invalidation</h3>
 * {@link #evictStoreRoleCache(Long, Long)} phải được gọi sau mỗi thao tác thay đổi role
 * trong {@link StoreService}.
 *
 * <h3>Cách dùng trong controller</h3>
 * <pre>{@code
 * @PreAuthorize("@storeAccess.isOwnerOrManager(#storeId, authentication)")
 * public ResponseEntity<?> updateStore(@PathVariable Long storeId, ...) { ... }
 * }</pre>
 * Bean name {@code "storeAccess"} khớp với {@code @storeAccess} trong SpEL expression.
 * {@code @EnableMethodSecurity} trong {@link SecurityConfig}
 * là điều kiện để {@code @PreAuthorize} hoạt động.
 */
@Component("storeAccess")
@RequiredArgsConstructor
public class StoreAccessEvaluator {

    private final UserRoleRepository userRoleRepository;
    private final StoreRepository storeRepository;
    private final StringRedisTemplate redisTemplate;

    /**
     * TTL của Redis cache tính bằng giây, đọc từ {@code store.role.cache.ttl}.
     * Mặc định 300 giây (5 phút) — cân bằng giữa consistency và DB load.
     */
    @Value("${store.role.cache.ttl:300}")
    private long cacheTtlSeconds;

    /**
     * Kiểm tra user có thuộc store không — business OWNER hoặc MANAGER/STAFF của store.
     */
    public boolean isMember(Long storeId, Authentication authentication) {
        return hasAccess(storeId, authentication, RoleName.ROLE_MANAGER, RoleName.ROLE_STAFF);
    }

    /**
     * Kiểm tra user có quyền quản lý store không — business OWNER hoặc MANAGER của store.
     */
    public boolean isOwnerOrManager(Long storeId, Authentication authentication) {
        return hasAccess(storeId, authentication, RoleName.ROLE_MANAGER);
    }

    /**
     * Kiểm tra user có quyền quản trị store này không — business OWNER hoặc BUSINESS_MANAGER
     * (trợ lý cấp business được quản nhân sự + cài đặt của mọi store trong business).
     */
    public boolean isOwner(Long storeId, Authentication authentication) {
        if (authentication == null) return false;
        if (isSuperAdmin(authentication)) return true;
        UserPrincipal principal = extractPrincipal(authentication);
        if (principal == null) return false;
        Long businessId = resolveBusinessId(storeId);
        return businessId != null && hasBusinessRoleWithCache(principal.userId(), businessId);
    }

    /**
     * Xóa cache role của một user trong một store cụ thể.
     *
     * <p>Phải gọi sau khi transaction DB commit — nếu gọi trước commit,
     * request tiếp theo sẽ cache miss và đọc lại DB, có thể thấy dữ liệu cũ
     * nếu transaction chưa commit xong.
     *
     * <p>Fail-safe: nếu Redis down, bỏ qua exception — cache entry cũ sẽ
     * tự hết hạn sau {@code cacheTtlSeconds} giây.
     *
     * @param userId  ID của user vừa bị thay đổi role
     * @param storeId ID của store liên quan
     */
    public void evictStoreRoleCache(Long userId, Long storeId) {
        try {
            redisTemplate.delete(storeRoleCacheKey(userId, storeId));
        } catch (Exception ignored) {
            // Redis down — cache sẽ expire tự nhiên sau TTL
        }
    }

    /**
     * Kiểm tra quyền: business OWNER của store's business OR store-level role trong {@code allowedStoreRoles}.
     *
     * <p>Business OWNER được kiểm tra trước để thoát sớm nếu user là OWNER.
     * Store-level role dùng Redis cache + DB fallback.
     */
    private boolean hasAccess(Long storeId, Authentication authentication, RoleName... allowedStoreRoles) {
        if (authentication == null) return false;
        if (isSuperAdmin(authentication)) return true;

        UserPrincipal principal = extractPrincipal(authentication);
        if (principal == null) return false;

        // Kiểm tra business-level role (OWNER/BUSINESS_MANAGER) — dùng cache thay vì DB trực tiếp
        Long businessId = resolveBusinessId(storeId);
        if (businessId != null && hasBusinessRoleWithCache(principal.userId(), businessId)) {
            return true;
        }

        // Kiểm tra store-level role (MANAGER/STAFF) qua Redis cache
        RoleName storeRole = resolveStoreRoleWithCache(principal.userId(), storeId);
        return storeRole != null && Arrays.asList(allowedStoreRoles).contains(storeRole);
    }

    /**
     * Lấy role của user trong store: ưu tiên Redis, fallback về DB nếu cache miss hoặc Redis down.
     *
     * <p>Hai khối try-catch độc lập: khối đầu cho read (fallback về DB),
     * khối sau cho write (bỏ qua nếu fail, DB đã trả kết quả rồi).
     */
    private RoleName resolveStoreRoleWithCache(Long userId, Long storeId) {
        String key = storeRoleCacheKey(userId, storeId);

        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return RoleName.valueOf(cached);
        } catch (Exception ignored) {
            // Redis down — tiếp tục xuống DB
        }

        var ur = userRoleRepository.findActiveStoreRole(userId, storeId);
        if (ur.isEmpty()) return null;

        RoleName role = ur.get().getRole().getName();

        try {
            redisTemplate.opsForValue().set(key, role.name(), cacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Redis down — bỏ qua
        }
        return role;
    }

    /**
     * Lấy businessId của một store — ưu tiên Redis cache {@code store:business:{storeId}},
     * fallback về DB. Cache businessId để tránh DB call mỗi request kiểm tra OWNER.
     */
    private Long resolveBusinessId(Long storeId) {
        String key = "store:business:" + storeId;

        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return Long.parseLong(cached);
        } catch (Exception ignored) {
            // Redis down — tiếp tục xuống DB
        }

        Long businessId = storeRepository.findBusinessIdByStoreId(storeId).orElse(null);

        if (businessId != null) {
            try {
                redisTemplate.opsForValue().set(key, businessId.toString(), cacheTtlSeconds, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Redis down — bỏ qua
            }
        }
        return businessId;
    }

    private static boolean isSuperAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> RoleName.ROLE_SUPER_ADMIN.name().equals(a.getAuthority()));
    }

    private static UserPrincipal extractPrincipal(Authentication authentication) {
        return authentication.getPrincipal() instanceof UserPrincipal p ? p : null;
    }

    /**
     * Kiểm tra user có business-level role (OWNER hoặc BUSINESS_MANAGER) trong business không —
     * dùng chung key {@code business:role:{userId}:{businessId}} với {@link BusinessAccessEvaluator}.
     *
     * <p>Cache lưu <b>tên role thật</b> (không hardcode ROLE_OWNER) để BusinessAccessEvaluator
     * phân biệt được OWNER vs BUSINESS_MANAGER khi gác thao tác owner-only. Ở store context thì
     * cả hai role đều được toàn quyền nên chỉ cần biết "có business role hay không".
     */
    private boolean hasBusinessRoleWithCache(Long userId, Long businessId) {
        String key = businessRoleCacheKey(userId, businessId);

        try {
            if (redisTemplate.opsForValue().get(key) != null) return true;
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

        return role != null;
    }

    /**
     * Redis key cho store-level role: {@code store:role:{userId}:{storeId}}.
     */
    private static String storeRoleCacheKey(Long userId, Long storeId) {
        return "store:role:" + userId + ":" + storeId;
    }

    /**
     * Redis key cho business-level OWNER role: {@code business:role:{userId}:{businessId}}.
     * Dùng chung với {@link BusinessAccessEvaluator} — evict qua {@code businessAccess.evictBusinessRoleCache()}.
     */
    private static String businessRoleCacheKey(Long userId, Long businessId) {
        return "business:role:" + userId + ":" + businessId;
    }
}
