package com.quiktech.backend.security;

/**
 * Đối tượng principal được lưu trong {@code SecurityContext} sau khi xác thực JWT thành công.
 *
 * <p>Thay thế {@code User} entity làm principal — tránh việc load toàn bộ entity từ DB
 * chỉ để đặt vào SecurityContext. Mọi thông tin cần thiết ({@code userId}, {@code username})
 * được extract thẳng từ JWT claims bởi {@link JwtAuthFilter}, không tốn DB call.
 *
 * <h3>Cách lấy principal trong controller / service</h3>
 * <pre>{@code
 * // Controller — inject tự động qua Spring Security argument resolver
 * public ResponseEntity<?> example(@AuthenticationPrincipal UserPrincipal currentUser) {
 *     Long userId = currentUser.userId();
 * }
 *
 * // Service / bất kỳ đâu — đọc từ SecurityContext
 * Authentication auth = SecurityContextHolder.getContext().getAuthentication();
 * UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
 * }</pre>
 *
 * <h3>Lý do dùng record</h3>
 * Record là immutable và có sẵn {@code equals}, {@code hashCode}, {@code toString} —
 * phù hợp cho value object không cần thay đổi sau khi tạo.
 *
 * @param userId   internal ID của user trong DB (từ claim {@code userId} trong JWT)
 * @param username tên đăng nhập (từ claim {@code sub} trong JWT)
 * @param roles    global roles của user (từ claim {@code roles} trong JWT), ví dụ ["SUPER_ADMIN"]
 */
import java.util.List;

public record UserPrincipal(Long userId, String username, List<String> roles) {

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
