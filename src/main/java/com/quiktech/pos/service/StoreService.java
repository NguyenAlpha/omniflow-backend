package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.store.AddMemberRequest;
import com.quiktech.pos.dto.request.store.StoreCreateRequest;
import com.quiktech.pos.dto.request.store.UpdateMemberRequest;
import com.quiktech.pos.dto.response.store.StoreMemberResponse;
import com.quiktech.pos.dto.response.store.StoreResponse;
import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.Role;
import com.quiktech.pos.entity.Store;
import com.quiktech.pos.entity.StoreMember;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.entity.enums.RoleName;
import com.quiktech.pos.exception.ForbiddenException;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.repository.BusinessRepository;
import com.quiktech.pos.repository.RoleRepository;
import com.quiktech.pos.repository.StoreMemberRepository;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import com.quiktech.pos.security.BusinessAccessEvaluator;
import com.quiktech.pos.security.StoreAccessEvaluator;
import com.quiktech.pos.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final StoreAccessEvaluator storeAccessEvaluator;
    private final BusinessAccessEvaluator businessAccessEvaluator;
    private final SubscriptionLimitService subscriptionLimitService;

    @Transactional
    public StoreResponse createStore(Long businessId, StoreCreateRequest request, UserPrincipal currentUser) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));

        subscriptionLimitService.checkStoreLimit(businessId);

        Store store = Store.builder()
                .business(business)
                .name(request.name())
                .address(request.address())
                .phone(request.phone())
                .email(request.email())
                .build();

        return toStoreResponse(storeRepository.save(store));
    }

    @Transactional(readOnly = true)
    public StoreResponse getStore(Long storeId) {
        return toStoreResponse(findStoreOrThrow(storeId));
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> getBusinessStores(Long businessId) {
        return storeRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .map(this::toStoreResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> getStores(UserPrincipal currentUser) {
        boolean isAdmin = currentUser.hasRole(RoleName.ROLE_SUPER_ADMIN.name())
                || currentUser.hasRole(RoleName.ROLE_SUPPORT.name());
        if (isAdmin) {
            return storeRepository.findAll().stream()
                    .map(this::toStoreResponse)
                    .toList();
        }

        // Stores qua business OWNER role
        Map<Long, Store> combined = new LinkedHashMap<>();
        userRoleRepository.findActiveBusinessRolesForUser(currentUser.userId())
                .forEach(ur -> storeRepository.findByBusinessIdAndDeletedAtIsNull(ur.getBusiness().getId())
                        .forEach(s -> combined.put(s.getId(), s)));

        // Stores qua direct StoreMember — JOIN FETCH store để tránh N+1
        storeMemberRepository.findByUserIdAndDeletedAtIsNullWithStore(currentUser.userId())
                .forEach(m -> combined.put(m.getStore().getId(), m.getStore()));

        return combined.values().stream().map(this::toStoreResponse).toList();
    }

    @Transactional
    public StoreResponse setStoreStatus(Long storeId, boolean isActive) {
        Store store = findStoreOrThrow(storeId);
        store.setIsActive(isActive);
        return toStoreResponse(storeRepository.save(store));
    }

    @Transactional
    public StoreResponse updateStore(Long storeId, StoreCreateRequest request) {
        Store store = findStoreOrThrow(storeId);
        store.setName(request.name());
        store.setAddress(request.address());
        store.setPhone(request.phone());
        store.setEmail(request.email());
        return toStoreResponse(storeRepository.save(store));
    }

    @Transactional(readOnly = true)
    public List<StoreMemberResponse> getMembers(Long storeId) {
        findStoreOrThrow(storeId);

        List<StoreMember> members = storeMemberRepository.findByStoreIdAndIsActiveAndDeletedAtIsNull(storeId, true);
        Map<Long, UserRole> roleByUserId = userRoleRepository
                .findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(storeId)
                .stream()
                // merge (a, b) -> a: nếu dữ liệu bẩn có 2 role active cùng user trong store
                // (đã bị chặn bởi ux_user_roles_user_store nhưng vẫn phòng thủ),
                // toMap không có merge function sẽ ném IllegalStateException → API sập
                .collect(Collectors.toMap(ur -> ur.getUser().getId(), ur -> ur, (a, b) -> a));

        return members.stream()
                .map(m -> toMemberResponse(m, roleByUserId.get(m.getUser().getId())))
                .toList();
    }

    @Transactional
    public StoreMemberResponse addMember(Long storeId, AddMemberRequest request) {
        findStoreOrThrow(storeId);
        validateStoreMemberRole(request.role());

        // Check duplicate qua StoreMember (chỉ filter deletedAt) — check cũ bằng
        // findActiveStoreRole bị bypass khi member tồn tại với isActive=false
        // (query đó filter isActive=true) → tạo được cặp StoreMember/UserRole trùng
        if (storeMemberRepository.findActiveStoreMember(storeId, request.userId()).isPresent()) {
            throw new IllegalArgumentException("User is already a member of this store");
        }

        User targetUser = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User not found"));

        Store store = findStoreOrThrow(storeId);
        subscriptionLimitService.checkStaffLimit(store.getBusiness().getId());

        StoreMember member = StoreMember.builder()
                .user(targetUser)
                .store(store)
                .positionTitle(request.positionTitle())
                .joinedDate(LocalDate.now())
                .isActive(request.isActive())
                .publicId(UUID.randomUUID())
                .build();
        storeMemberRepository.save(member);

        UserRole userRole = UserRole.builder()
                .user(targetUser)
                .role(findRoleOrThrow(request.role()))
                .store(store)
                .isActive(request.isActive())
                .build();
        userRoleRepository.save(userRole);

        // Xóa cache cũ nếu user đã từng có role trong store này
        evictMemberCachesAfterCommit(request.userId(), storeId, store.getBusiness().getId());

        return toMemberResponse(member, userRole);
    }

    @Transactional
    public StoreMemberResponse updateMember(Long storeId, Long memberId, UpdateMemberRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);
        validateStoreMemberRole(request.role());

        // Scoped theo storeId — chống IDOR xuyên tenant (xem Javadoc repository method)
        StoreMember member = storeMemberRepository.findByIdAndStoreIdAndDeletedAtIsNull(memberId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findActiveStoreRole(member.getUser().getId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member role not found"));

        userRole.setRole(findRoleOrThrow(request.role()));
        userRole.setIsActive(request.isActive());
        userRoleRepository.save(userRole);

        member.setPositionTitle(request.positionTitle());
        member.setIsActive(request.isActive());
        storeMemberRepository.save(member);

        evictMemberCachesAfterCommit(member.getUser().getId(), storeId, store.getBusiness().getId());

        return toMemberResponse(member, userRole);
    }

    @Transactional
    public void removeMember(Long storeId, Long memberId) {
        Store store = findStoreOrThrow(storeId);

        // Scoped theo storeId — chống IDOR xuyên tenant (xem Javadoc repository method)
        StoreMember member = storeMemberRepository.findByIdAndStoreIdAndDeletedAtIsNull(memberId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findActiveStoreRole(member.getUser().getId(), storeId)
                .orElse(null);

        Instant now = Instant.now();
        member.setDeletedAt(now);
        storeMemberRepository.save(member);

        if (userRole != null) {
            userRole.setDeletedAt(now);
            userRoleRepository.save(userRole);
        }

        evictMemberCachesAfterCommit(member.getUser().getId(), storeId, store.getBusiness().getId());
    }

    /**
     * Đăng ký evict cache phân quyền SAU KHI transaction commit.
     *
     * <p>Phải after-commit vì nếu evict ngay giữa transaction: request khác chen vào
     * (sau evict, trước commit) sẽ cache-miss → đọc DB thấy role CŨ (chưa commit)
     * → ghi lại role cũ vào cache với TTL đầy đủ — quyền vừa gỡ "hồi sinh" tới 5 phút.
     * (Xem Javadoc {@code StoreAccessEvaluator.evictStoreRoleCache}.)
     *
     * <p>Evict cả 2 tầng: {@code store:role} (StoreAccessEvaluator — quyền theo store)
     * và {@code business:member} (BusinessAccessEvaluator — quyền catalog cấp business,
     * suy ra từ store membership nên phải invalidate cùng lúc).
     */
    private void evictMemberCachesAfterCommit(Long userId, Long storeId, Long businessId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storeAccessEvaluator.evictStoreRoleCache(userId, storeId);
                businessAccessEvaluator.evictBusinessMemberCache(userId, businessId);
            }
        });
    }

    /**
     * Store-scoped role chỉ được phép MANAGER/STAFF — request cho phép mọi RoleName nên
     * OWNER có thể gửi ROLE_OWNER/ROLE_SUPER_ADMIN. Không leo thang quyền được (authorities
     * chỉ lấy từ role global, business OWNER yêu cầu store IS NULL) nhưng tạo bản ghi
     * user_roles sai ngữ nghĩa: hasAccess chỉ nhận MANAGER/STAFF nên user thực tế mất
     * sạch quyền trong store — chặn từ đầu để không có trạng thái rác.
     */
    private void validateStoreMemberRole(RoleName role) {
        if (role != RoleName.ROLE_MANAGER && role != RoleName.ROLE_STAFF) {
            throw new IllegalArgumentException("Store member role must be ROLE_MANAGER or ROLE_STAFF");
        }
    }

    private Store findStoreOrThrow(Long storeId) {
        // Filter deletedAt để nhất quán với các query list (findByBusinessIdAndDeletedAtIsNull) —
        // hiện chưa có API xóa store, nhưng nếu sau này set deletedAt thì mọi endpoint
        // theo storeId không được truy cập store đã xóa
        return storeRepository.findByIdAndDeletedAtIsNull(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private Role findRoleOrThrow(RoleName roleName) {
        return roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role not found: " + roleName));
    }

    private StoreResponse toStoreResponse(Store store) {
        return new StoreResponse(
                store.getId(), store.getName(), store.getAddress(),
                store.getPhone(), store.getEmail(), store.getIsActive(),
                store.getCreatedAt(), store.getUpdatedAt()
        );
    }

    private StoreMemberResponse toMemberResponse(StoreMember m, UserRole userRole) {
        return new StoreMemberResponse(
                m.getId(), m.getPublicId(),
                m.getUser().getId(), m.getUser().getUsername(),
                m.getStore().getId(),
                userRole != null ? userRole.getRole().getName() : null,
                m.getPositionTitle(), m.getJoinedDate(),
                m.getIsActive(), m.getSyncVersion(), m.getLastModifiedAt()
        );
    }
}
