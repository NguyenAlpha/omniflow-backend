package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.business.BusinessCreateRequest;
import com.quiktech.pos.dto.response.business.BusinessDefaultResponse;
import com.quiktech.pos.dto.response.business.BusinessResponse;
import com.quiktech.pos.dto.response.subscription.SubscriptionResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.store.StoreResponse;
import com.quiktech.pos.dto.response.warehouse.WarehouseResponse;
import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.BusinessMember;
import com.quiktech.pos.entity.SubscriptionPlanConfig;
import com.quiktech.pos.entity.Role;
import com.quiktech.pos.entity.Store;
import com.quiktech.pos.entity.Subscription;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.entity.Warehouse;
import com.quiktech.pos.entity.enums.RoleName;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.BusinessMemberRepository;
import com.quiktech.pos.repository.BusinessRepository;
import com.quiktech.pos.repository.RoleRepository;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.SubscriptionRepository;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import com.quiktech.pos.repository.WarehouseRepository;
import com.quiktech.pos.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BusinessService {

    private final BusinessRepository businessRepository;
    private final BusinessMemberRepository businessMemberRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final StoreRepository storeRepository;
    private final WarehouseRepository warehouseRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanCatalogService planCatalogService;

    @Transactional
    public BusinessResponse createBusiness(BusinessCreateRequest request, UserPrincipal currentUser) {
        Business business = Business.builder()
                .name(request.name())
                .address(request.address())
                .phone(request.phone())
                .email(request.email())
                .build();
        businessRepository.save(business);

        subscriptionRepository.save(buildFreeSubscription(business));

        User userRef = userRepository.getReferenceById(currentUser.userId());

        businessMemberRepository.save(BusinessMember.builder()
                .user(userRef)
                .business(business)
                .joinedDate(LocalDate.now())
                .isActive(true)
                .publicId(UUID.randomUUID())
                .build());

        userRoleRepository.save(UserRole.builder()
                .user(userRef)
                .role(findRoleOrThrow(RoleName.ROLE_OWNER))
                .business(business)
                .isActive(true)
                .build());

        return toBusinessResponse(business);
    }

    /**
     * Tạo business mặc định kèm store và warehouse đầu tiên — gọi khi user đăng ký mới.
     * OWNER role được gán ở business level (business_id = business.id, store_id = null).
     */
    @Transactional
    public BusinessDefaultResponse createDefaultBusiness(UserPrincipal currentUser) {
        // Idempotent: endpoint thuộc luồng đăng ký, client có thể retry (mất mạng,
        // double-tap) — nếu user đã là OWNER của business nào đó thì trả về business
        // hiện có thay vì tạo thêm bộ business/store/warehouse/subscription trùng lặp
        List<UserRole> ownerRoles = userRoleRepository.findActiveBusinessRolesForUser(currentUser.userId());
        if (!ownerRoles.isEmpty()) {
            Business existing = ownerRoles.get(0).getBusiness();
            Store existingStore = storeRepository.findByBusinessIdAndDeletedAtIsNull(existing.getId())
                    .stream().findFirst().orElse(null);
            Warehouse existingWarehouse = existingStore == null ? null
                    : warehouseRepository.findByStoreIdAndDeletedAtIsNull(existingStore.getId())
                            .stream().findFirst().orElse(null);
            return new BusinessDefaultResponse(
                    toBusinessResponse(existing),
                    existingStore == null ? null : toStoreResponse(existingStore),
                    existingWarehouse == null ? null : toWarehouseResponse(existingWarehouse));
        }

        Business business = Business.builder()
                .name("Doanh nghiệp của tôi")
                .isActive(true)
                .build();
        businessRepository.save(business);

        subscriptionRepository.save(buildFreeSubscription(business));

        Store store = Store.builder()
                .business(business)
                .name("Cửa hàng số 1")
                .address("")
                .phone("")
                .email("")
                .build();
        storeRepository.save(store);

        Warehouse warehouse = Warehouse.builder()
                .store(store)
                .name("Kho số 1")
                .publicId(UUID.randomUUID())
                .build();
        warehouseRepository.save(warehouse);

        User userRef = userRepository.getReferenceById(currentUser.userId());

        businessMemberRepository.save(BusinessMember.builder()
                .user(userRef)
                .business(business)
                .joinedDate(LocalDate.now())
                .isActive(true)
                .publicId(UUID.randomUUID())
                .build());

        // OWNER role tại business level — không cần StoreMember vì OWNER truy cập qua business role
        userRoleRepository.save(UserRole.builder()
                .user(userRef)
                .role(findRoleOrThrow(RoleName.ROLE_OWNER))
                .business(business)
                .isActive(true)
                .build());

        return new BusinessDefaultResponse(
                toBusinessResponse(business),
                toStoreResponse(store),
                toWarehouseResponse(warehouse)
        );
    }

    @Transactional(readOnly = true)
    public BusinessResponse getBusiness(Long businessId) {
        return toBusinessResponse(findBusinessOrThrow(businessId));
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getSubscription(Long businessId) {
        Subscription sub = subscriptionRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Subscription not found"));
        return toSubscriptionResponse(sub);
    }

    @Transactional(readOnly = true)
    public List<BusinessResponse> getBusinesses(UserPrincipal currentUser) {
        boolean isAdmin = currentUser.hasRole(RoleName.ROLE_SUPER_ADMIN.name())
                || currentUser.hasRole(RoleName.ROLE_SUPPORT.name());
        if (isAdmin) {
            return businessRepository.findAll().stream().map(this::toBusinessResponse).toList();
        }

        Map<Long, Business> combined = new LinkedHashMap<>();

        // OWNERs — business-level UserRole, JOIN FETCH business đã có sẵn trong query
        userRoleRepository.findActiveBusinessRolesForUser(currentUser.userId())
                .forEach(ur -> combined.put(ur.getBusiness().getId(), ur.getBusiness()));

        // MANAGER/STAFF — store-level UserRole → store → business, JOIN FETCH đã có sẵn
        userRoleRepository.findActiveStoreRolesWithBusinessDetails(currentUser.userId())
                .forEach(ur -> combined.put(ur.getStore().getBusiness().getId(), ur.getStore().getBusiness()));

        return combined.values().stream().map(this::toBusinessResponse).toList();
    }

    @Transactional
    public BusinessResponse updateBusiness(Long businessId, BusinessCreateRequest request) {
        Business business = findBusinessOrThrow(businessId);
        business.setName(request.name());
        business.setAddress(request.address());
        business.setPhone(request.phone());
        business.setEmail(request.email());
        return toBusinessResponse(businessRepository.save(business));
    }

    @Transactional
    public BusinessResponse setBusinessStatus(Long businessId, boolean isActive) {
        Business business = findBusinessOrThrow(businessId);
        business.setIsActive(isActive);
        return toBusinessResponse(businessRepository.save(business));
    }

    private SubscriptionResponse toSubscriptionResponse(Subscription sub) {
        return new SubscriptionResponse(
                sub.getId(), sub.getBusiness().getId(),
                sub.getPlan(), sub.getStatus(), sub.getBillingCycle(),
                sub.getMaxStores(), sub.getMaxStaff(), sub.getMaxProducts(), sub.getMaxWarehouses(),
                sub.getStartedAt(), sub.getExpiresAt(),
                sub.getCreatedAt(), sub.getUpdatedAt(),
                sub.getPendingPlan(), sub.getPendingBillingCycle()
        );
    }

    private Subscription buildFreeSubscription(Business business) {
        // Đọc limit FREE từ bảng subscription_plans (single source of truth) — trước đây
        // hardcode 1,0,50,1 tạo 2 nguồn chân lý, admin sửa gói sẽ không có tác dụng ở đây
        SubscriptionPlanConfig free = planCatalogService.limitsFor(SubscriptionPlan.FREE);
        return Subscription.builder()
                .business(business)
                .plan(SubscriptionPlan.FREE)
                .status(SubscriptionStatus.ACTIVE)
                .maxStores(free.getMaxStores())
                .maxStaff(free.getMaxStaff())
                .maxProducts(free.getMaxProducts())
                .maxWarehouses(free.getMaxWarehouses())
                .startedAt(Instant.now())
                .build();
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    private Role findRoleOrThrow(RoleName roleName) {
        return roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role not found: " + roleName));
    }

    private BusinessResponse toBusinessResponse(Business business) {
        return new BusinessResponse(
                business.getId(), business.getName(), business.getAddress(),
                business.getPhone(), business.getEmail(), business.getIsActive(),
                business.getCreatedAt(), business.getUpdatedAt()
        );
    }

    private StoreResponse toStoreResponse(Store store) {
        return new StoreResponse(
                store.getId(), store.getName(), store.getAddress(),
                store.getPhone(), store.getEmail(), store.getIsActive(),
                store.getCreatedAt(), store.getUpdatedAt()
        );
    }

    private WarehouseResponse toWarehouseResponse(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getId(), warehouse.getPublicId(),
                warehouse.getStore().getId(), warehouse.getName(),
                warehouse.getAddress(), warehouse.getIsActive(),
                warehouse.getSyncVersion(), warehouse.getLastModifiedAt(),
                warehouse.getCreatedAt(), warehouse.getUpdatedAt()
        );
    }
}
