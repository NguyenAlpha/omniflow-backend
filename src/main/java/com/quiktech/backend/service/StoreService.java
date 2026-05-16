package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.store.AddMemberRequest;
import com.quiktech.backend.dto.request.store.StoreCreateRequest;
import com.quiktech.backend.dto.request.store.UpdateMemberRequest;
import com.quiktech.backend.dto.response.store.StoreMemberResponse;
import com.quiktech.backend.dto.response.store.StoreResponse;
import com.quiktech.backend.entity.Role;
import com.quiktech.backend.entity.Store;
import com.quiktech.backend.entity.StoreMember;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.entity.UserRole;
import com.quiktech.backend.entity.enums.RoleName;
import com.quiktech.backend.exception.ForbiddenException;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.repository.RoleRepository;
import com.quiktech.backend.repository.StoreMemberRepository;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.repository.UserRoleRepository;
import com.quiktech.backend.security.StoreAccessEvaluator;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final StoreAccessEvaluator storeAccessEvaluator;

    @Transactional
    public StoreResponse createStore(StoreCreateRequest request, UserPrincipal currentUser) {
        Store store = Store.builder()
                .name(request.name())
                .address(request.address())
                .phone(request.phone())
                .email(request.email())
                .build();
        storeRepository.save(store);

        // getReferenceById tạo JPA proxy — không cần SELECT, chỉ cần ID cho FK
        User userRef = userRepository.getReferenceById(currentUser.userId());

        StoreMember member = StoreMember.builder()
                .user(userRef)
                .store(store)
                .isActive(true)
                .publicId(UUID.randomUUID())
                .build();
        storeMemberRepository.save(member);

        userRoleRepository.save(UserRole.builder()
                .user(userRef)
                .role(findRoleOrThrow(RoleName.ROLE_OWNER))
                .store(store)
                .isActive(true)
                .build());

        return toStoreResponse(store);
    }

    @Transactional(readOnly = true)
    public StoreResponse getStore(Long storeId) {
        return toStoreResponse(findStoreOrThrow(storeId));
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
        return storeMemberRepository.findByUserIdAndDeletedAtIsNull(currentUser.userId())
                .stream()
                .map(m -> toStoreResponse(m.getStore()))
                .toList();
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
                .collect(Collectors.toMap(ur -> ur.getUser().getId(), ur -> ur));

        return members.stream()
                .map(m -> toMemberResponse(m, roleByUserId.get(m.getUser().getId())))
                .toList();
    }

    @Transactional
    public StoreMemberResponse addMember(Long storeId, AddMemberRequest request) {
        findStoreOrThrow(storeId);

        if (userRoleRepository.findActiveStoreRole(request.userId(), storeId).isPresent()) {
            throw new IllegalArgumentException("User is already a member of this store");
        }

        User targetUser = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User not found"));

        Store store = findStoreOrThrow(storeId);

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
        storeAccessEvaluator.evictStoreRoleCache(request.userId(), storeId);

        return toMemberResponse(member, userRole);
    }

    @Transactional
    public StoreMemberResponse updateMember(Long storeId, Long memberId, UpdateMemberRequest request, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);

        StoreMember member = storeMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findActiveStoreRole(member.getUser().getId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member role not found"));

        if (RoleName.ROLE_OWNER == userRole.getRole().getName() && !member.getUser().getId().equals(currentUser.userId())) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot modify another OWNER");
        }

        userRole.setRole(findRoleOrThrow(request.role()));
        userRole.setIsActive(request.isActive());
        userRoleRepository.save(userRole);

        member.setPositionTitle(request.positionTitle());
        member.setIsActive(request.isActive());
        storeMemberRepository.save(member);

        storeAccessEvaluator.evictStoreRoleCache(member.getUser().getId(), storeId);

        return toMemberResponse(member, userRole);
    }

@Transactional
    public void removeMember(Long storeId, Long memberId) {
        findStoreOrThrow(storeId);

        StoreMember member = storeMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findActiveStoreRole(member.getUser().getId(), storeId)
                .orElse(null);

        if (userRole != null && RoleName.ROLE_OWNER == userRole.getRole().getName()) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot remove the OWNER from store");
        }

        Instant now = Instant.now();
        member.setDeletedAt(now);
        storeMemberRepository.save(member);

        if (userRole != null) {
            userRole.setDeletedAt(now);
            userRoleRepository.save(userRole);
        }

        storeAccessEvaluator.evictStoreRoleCache(member.getUser().getId(), storeId);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
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
