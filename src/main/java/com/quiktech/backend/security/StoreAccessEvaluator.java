package com.quiktech.backend.security;

import com.quiktech.backend.config.SecurityConfig;
import com.quiktech.backend.entity.enums.RoleName;
import com.quiktech.backend.repository.UserRoleRepository;
import com.quiktech.backend.service.StoreService;
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
 * {@code SecurityContext} thông qua JWT claims. Store-scoped role ({@code OWNER},
 * {@code MANAGER}, {@code STAFF}) phụ thuộc vào từng store nên không thể nhúng vào
 * JWT — phải kiểm tra riêng theo từng request.
 *
 * <h3>Luồng kiểm tra quyền</h3>
 * <pre>
 * hasStoreRole(storeId, auth, allowed...)
 *   ↓
 * auth == null ?  → false
 * SUPER_ADMIN ?   → true  (bypass mọi check)
 *   ↓
 * Lấy UserPrincipal từ authentication.getPrincipal()
 *   ↓
 * Đọc Redis key "store:role:{userId}:{storeId}"
 *   ├── Hit  → parse RoleName → kiểm tra trong allowed[]
 *   └── Miss → query DB (findActiveStoreRole, JOIN FETCH role)
 *                ↓
 *             Ghi Redis với TTL (mặc định 300 giây)
 *                ↓
 *             Kiểm tra trong allowed[]
 * </pre>
 *
 * <h3>Redis down — Graceful degradation</h3>
 * Mọi thao tác Redis đều được bọc try-catch. Khi Redis không available:
 * <ul>
 *   <li>Read fail → tự động fallback về DB (không ném exception, không trả {@code false} sai)</li>
 *   <li>Write fail → bỏ qua, kết quả DB đã có — cache miss lần sau cũng fallback DB</li>
 *   <li>Evict fail → bỏ qua, entry cũ sẽ tự expire sau TTL</li>
 * </ul>
 *
 * <h3>Cache invalidation</h3>
 * {@link #evictStoreRoleCache(Long, Long)} phải được gọi sau mỗi thao tác thay đổi role
 * (add/update/remove member) trong {@link StoreService}.
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
    private final StringRedisTemplate redisTemplate;

    /**
     * TTL của Redis cache tính bằng giây, đọc từ {@code store.role.cache.ttl}.
     * Mặc định 300 giây (5 phút) — cân bằng giữa consistency và DB load.
     */
    @Value("${store.role.cache.ttl:300}")
    private long cacheTtlSeconds;

    /**
     * Kiểm tra user có thuộc store không (bất kỳ role nào: OWNER, MANAGER, STAFF).
     *
     * @param storeId        ID của store cần kiểm tra
     * @param authentication đối tượng auth từ {@code SecurityContext}
     * @return {@code true} nếu user có ít nhất một role active trong store
     */
    public boolean isMember(Long storeId, Authentication authentication) {
        return hasStoreRole(storeId, authentication, RoleName.ROLE_OWNER, RoleName.ROLE_MANAGER, RoleName.ROLE_STAFF);
    }

    /**
     * Kiểm tra user có quyền quản lý store không (OWNER hoặc MANAGER).
     *
     * @param storeId        ID của store cần kiểm tra
     * @param authentication đối tượng auth từ {@code SecurityContext}
     * @return {@code true} nếu user có role OWNER hoặc MANAGER
     */
    public boolean isOwnerOrManager(Long storeId, Authentication authentication) {
        return hasStoreRole(storeId, authentication, RoleName.ROLE_OWNER, RoleName.ROLE_MANAGER);
    }

    /**
     * Kiểm tra user có phải OWNER của store không.
     *
     * @param storeId        ID của store cần kiểm tra
     * @param authentication đối tượng auth từ {@code SecurityContext}
     * @return {@code true} nếu user có role OWNER
     */
    public boolean isOwner(Long storeId, Authentication authentication) {
        return hasStoreRole(storeId, authentication, RoleName.ROLE_OWNER);
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
            redisTemplate.delete(cacheKey(userId, storeId));
        } catch (Exception ignored) {
            // Redis down — cache sẽ expire tự nhiên sau TTL
        }
    }

    /**
     * Hàm kiểm tra quyền dùng chung cho {@link #isMember}, {@link #isOwnerOrManager}, {@link #isOwner}.
     *
     * <p>Thứ tự kiểm tra được tối ưu: guard clause trước, SUPER_ADMIN bypass trước,
     * Redis trước DB — để thoát sớm trong trường hợp phổ biến nhất.
     *
     * @param storeId        ID của store
     * @param authentication auth object từ SecurityContext
     * @param allowed        danh sách role được phép truy cập
     * @return {@code true} nếu user có role nằm trong {@code allowed}
     */
    private boolean hasStoreRole(Long storeId, Authentication authentication, RoleName... allowed) {
        if (authentication == null) return false;

        // SUPER_ADMIN có quyền truy cập mọi store — không cần kiểm tra DB hay cache
        if (isSuperAdmin(authentication)) return true;

        UserPrincipal principal = extractPrincipal(authentication);
        if (principal == null) return false;

        String key = cacheKey(principal.userId(), storeId);

        RoleName role = resolveRoleWithCache(key, principal.userId(), storeId);
        if (role == null) return false; // user không phải member của store

        return Arrays.asList(allowed).contains(role);
    }

    /**
     * Lấy role của user trong store: ưu tiên Redis, fallback về DB nếu cache miss hoặc Redis down.
     *
     * <p>Hai khối try-catch độc lập: khối đầu cho read (fallback về DB),
     * khối sau cho write (bỏ qua nếu fail, DB đã trả kết quả rồi).
     *
     * @param key     Redis cache key cho cặp (userId, storeId)
     * @param userId  ID của user
     * @param storeId ID của store
     * @return role hiện tại của user; {@code null} nếu không phải member
     */
    private RoleName resolveRoleWithCache(String key, Long userId, Long storeId) {
        // Bước 1: thử đọc từ Redis
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) return RoleName.valueOf(cached); // cache hit — trả về ngay
        } catch (Exception ignored) {
            // Redis down — tiếp tục xuống DB, không trả false sai
        }

        // Bước 2: cache miss hoặc Redis down — query DB với JOIN FETCH (không N+1)
        var ur = userRoleRepository.findActiveStoreRole(userId, storeId);
        if (ur.isEmpty()) return null; // user không phải member hoặc role đã bị xóa

        RoleName role = ur.get().getRole().getName();

        // Bước 3: lưu vào Redis để cache hit cho lần sau
        try {
            redisTemplate.opsForValue().set(key, role.name(), cacheTtlSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Redis down — bỏ qua, kết quả DB đã đủ để trả về
        }
        return role;
    }

    /**
     * Kiểm tra trong authorities của {@code SecurityContext} có {@code ROLE_SUPER_ADMIN} không.
     * Authorities này được extract từ JWT claims bởi {@link UserPrincipalConverter} — không cần DB.
     */
    private static boolean isSuperAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> RoleName.ROLE_SUPER_ADMIN.name().equals(a.getAuthority()));
    }

    /**
     * Lấy {@link UserPrincipal} từ principal của {@code Authentication}.
     *
     * <p>Trả về {@code null} nếu principal không phải {@code UserPrincipal} —
     * ví dụ khi request đến từ anonymous user hoặc authentication được tạo bởi
     * cơ chế khác (test context, basic auth, ...).
     */
    private static UserPrincipal extractPrincipal(Authentication authentication) {
        return authentication.getPrincipal() instanceof UserPrincipal p ? p : null;
    }

    /**
     * Tạo Redis key cho cặp (userId, storeId).
     * Format: {@code store:role:{userId}:{storeId}} — namespace rõ ràng, tránh collision với key khác.
     */
    private static String cacheKey(Long userId, Long storeId) {
        return "store:role:" + userId + ":" + storeId;
    }
}
