package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.auth.AuthResponse;
import com.quiktech.pos.dto.response.auth.BusinessMembershipResponse;
import com.quiktech.pos.dto.response.auth.StoreInfo;
import com.quiktech.pos.dto.response.auth.UserSummaryResponse;
import com.quiktech.pos.entity.StoreMember;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.repository.StoreMemberRepository;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import com.quiktech.pos.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dựng {@link AuthResponse} trả về sau register/login/refresh: JWT (userId + global roles)
 * + user summary + business memberships. Tách khỏi {@link AuthService} để service đó chỉ lo
 * xác thực (kiểm tra mật khẩu, refresh token), còn class này chỉ lo gom dữ liệu trả về.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthResponseAssembler {

    private final StoreMemberRepository storeMemberRepository;
    private final StoreRepository storeRepository;
    private final UserRoleRepository userRoleRepository;
    private final JwtService jwtService;

    // Thời hạn access token tính bằng mili giây, đọc từ application.properties
    // (jwt.expiration = ${JWT_EXPIRATION:3600000} → mặc định 1 giờ)
    @Value("${jwt.expiration}")
    private long jwtExpiration;

    /**
     * Dựng toàn bộ response đăng nhập cho một user đã xác thực xong.
     *
     * <p>OWNER/BUSINESS_MANAGER → tất cả stores của business; MANAGER/STAFF → stores được
     * assign, grouped by business.
     *
     * @param user         user đã xác thực (register/login/refresh thành công)
     * @param refreshToken refresh token vừa tạo cho lần đăng nhập này — chỉ được đính kèm
     *                     vào response, class này không tạo hay lưu token
     * @return response gửi về client: access token (JWT), {@code expiresIn} (giây), thông tin
     *         user, memberships và refresh token
     */
    public AuthResponse assemble(User user, String refreshToken) {
        List<BusinessMembershipResponse> memberships = resolveMemberships(user);
        List<String> globalRoles = resolveGlobalRoles(user);

        log.info("Building token for userId={}: globalRoles={}, businessCount={}",
                user.getId(), globalRoles, memberships.size());

        // Tạo JWT ký bằng HS256 (JwtService): subject = username, thêm 2 claim riêng là userId
        // và roles. Khi client gửi JWT lên, UserPrincipalConverter đọc lại 2 claim này để biết
        // "ai đang gọi" mà không cần query DB.
        String token = jwtService.generateToken(user, Map.of(
                "userId", user.getId(),
                "roles", globalRoles
        ));

        UserSummaryResponse userSummary = new UserSummaryResponse(
                user.getId(), user.getUsername(), user.getEmail(),
                user.getFullName(), user.getPhone(), user.getIsActive()
        );

        // jwtExpiration là mili giây → chia 1000 để expiresIn trả về client tính bằng giây
        return new AuthResponse(token, "Bearer", jwtExpiration / 1000, userSummary, memberships, refreshToken);
    }

    /**
     * Liệt kê các business user thuộc về và các store user được vào trong từng business.
     *
     * <p>Có 2 nguồn quyền, cho ra 2 nhóm entry:
     * <ol>
     *   <li><b>Role cấp business</b> (OWNER / BUSINESS_MANAGER, {@code user_roles.store_id IS NULL}):
     *       được vào mọi store của business, nên liệt kê toàn bộ store chưa xóa.</li>
     *   <li><b>Role cấp store</b> (MANAGER / STAFF, {@code user_roles.store_id IS NOT NULL}):
     *       chỉ liệt kê các store được gán, gom theo business, kèm chức danh của từng store.</li>
     * </ol>
     * Entry cấp business đứng trước. Chỉ tính role đang active và chưa bị xóa.
     *
     * @param user user cần liệt kê quyền
     * @return danh sách membership — mỗi phần tử là 1 business kèm các store của nó; rỗng nếu
     *         user chưa thuộc business nào (VD vừa đăng ký, hoặc chỉ là SUPER_ADMIN)
     */
    private List<BusinessMembershipResponse> resolveMemberships(User user) {
        List<BusinessMembershipResponse> memberships = new ArrayList<>();

        // Business OWNER / BUSINESS_MANAGER entries — role + business eagerly fetched
        List<UserRole> businessRoles = userRoleRepository.findActiveBusinessRolesForUser(user.getId());
        for (UserRole ur : businessRoles) {
            Long businessId = ur.getBusiness().getId();
            // 1 query lấy store cho mỗi business — chấp nhận được vì 1 user thường chỉ có vài business.
            // Role của từng store = role cấp business (OWNER/BUSINESS_MANAGER); chức danh luôn null
            // vì role cấp business không có bản ghi store_members.
            List<StoreInfo> storeInfos = storeRepository.findByBusinessIdAndDeletedAtIsNull(businessId)
                    .stream()
                    .map(s -> new StoreInfo(s.getId(), s.getName(), ur.getRole().getName(), null))
                    .toList();
            memberships.add(new BusinessMembershipResponse(businessId, ur.getBusiness().getName(), storeInfos));
        }

        // MANAGER / STAFF entries — role + store + store.business eagerly fetched
        List<UserRole> storeRoles = userRoleRepository.findActiveStoreRolesWithBusinessDetails(user.getId());
        if (!storeRoles.isEmpty()) {
            // Chức danh (positionTitle) nằm ở bảng store_members, không nằm ở user_roles →
            // tải 1 lần mọi store_members của user rồi đổi thành Map storeId → StoreMember
            // để tra nhanh ở dưới (tránh query riêng cho từng store)
            Map<Long, StoreMember> memberByStoreId = storeMemberRepository
                    .findByUserIdAndDeletedAtIsNullWithStore(user.getId())
                    .stream()
                    .collect(Collectors.toMap(m -> m.getStore().getId(), m -> m));

            // Gom các role theo businessId: Map<businessId, List<UserRole>>.
            // Dùng LinkedHashMap (không dùng HashMap mặc định) để giữ đúng thứ tự business
            // như kết quả query → thứ tự memberships trả về ổn định giữa các lần đăng nhập.
            storeRoles.stream()
                    .collect(Collectors.groupingBy(
                            ur -> ur.getStore().getBusiness().getId(),
                            LinkedHashMap::new,
                            Collectors.toList()))
                    // Mỗi business → 1 entry membership chứa các store user được gán trong business đó
                    .forEach((businessId, roles) -> {
                        // Mọi role trong nhóm cùng business nên lấy tên từ phần tử đầu là đủ
                        String businessName = roles.get(0).getStore().getBusiness().getName();
                        List<StoreInfo> storeInfos = roles.stream()
                                .map(ur -> {
                                    StoreMember m = memberByStoreId.get(ur.getStore().getId());
                                    return new StoreInfo(
                                            ur.getStore().getId(),
                                            ur.getStore().getName(),
                                            ur.getRole().getName(),
                                            // Phòng thủ: có role nhưng thiếu bản ghi store_members
                                            // (dữ liệu lệch) thì trả chức danh null thay vì lỗi
                                            m != null ? m.getPositionTitle() : null
                                    );
                                })
                                .toList();
                        memberships.add(new BusinessMembershipResponse(businessId, businessName, storeInfos));
                    });
        }
        return memberships;
    }

    /**
     * Lấy các role toàn hệ thống (global) của user để nhúng vào JWT.
     *
     * <p>Global role = role không gắn với business hay store nào
     * ({@code business_id IS NULL AND store_id IS NULL}), hiện gồm {@code ROLE_SUPER_ADMIN}
     * và {@code ROLE_SUPPORT}. Role cấp business/store <b>không</b> nhúng vào JWT vì chúng thay
     * đổi thường xuyên — server tra DB (có cache Redis) mỗi request qua
     * {@code BusinessAccessEvaluator}/{@code StoreAccessEvaluator}.
     *
     * @param user user cần lấy role
     * @return tên các role global đang active, VD {@code ["ROLE_SUPER_ADMIN"]}; rỗng với user thường
     */
    // Global roles nhúng vào JWT — UserPrincipalConverter extract, không cần DB call
    private List<String> resolveGlobalRoles(User user) {
        return userRoleRepository
                .findByUserIdAndBusinessIsNullAndStoreIsNullAndDeletedAtIsNull(user.getId())
                .stream()
                // Query trên chưa lọc is_active nên lọc ở đây. Boolean.TRUE.equals(...) thay vì
                // ur.getIsActive() == true để không bị NullPointerException nếu giá trị là null.
                .filter(ur -> Boolean.TRUE.equals(ur.getIsActive()))
                // RoleName là enum → .name() đổi thành chuỗi "ROLE_SUPER_ADMIN" để ghi vào JWT
                .map(ur -> ur.getRole().getName().name())
                .toList();
    }
}
