package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.store.StoreCreateRequest;
import com.omniflow.backend.dto.request.store.StoreMemberUpsertRequest;
import com.omniflow.backend.dto.response.store.StoreMemberResponse;
import com.omniflow.backend.dto.response.store.StoreResponse;
import com.omniflow.backend.entity.Role;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.StoreMember;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.UserRole;
import com.omniflow.backend.entity.enums.RoleName;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.repository.RoleRepository;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.repository.UserRepository;
import com.omniflow.backend.repository.UserRoleRepository;
import com.omniflow.backend.security.StoreAccessEvaluator;
import com.omniflow.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
                .role(findRoleOrThrow(RoleName.OWNER))
                .store(store)
                .isActive(true)
                .build());

        return toStoreResponse(store);
    }

    @Transactional(readOnly = true)
    public StoreResponse getStore(Long storeId, UserPrincipal currentUser) {
        return toStoreResponse(findStoreOrThrow(storeId));
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> getMyStores(UserPrincipal currentUser) {
        return storeMemberRepository.findByUserIdAndDeletedAtIsNull(currentUser.userId())
                .stream()
                .map(m -> toStoreResponse(m.getStore()))
                .toList();
    }

    @Transactional
    public StoreResponse updateStore(Long storeId, StoreCreateRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);
        store.setName(request.name());
        store.setAddress(request.address());
        store.setPhone(request.phone());
        store.setEmail(request.email());
        return toStoreResponse(storeRepository.save(store));
    }

    @Transactional(readOnly = true)
    public List<StoreMemberResponse> getMembers(Long storeId, UserPrincipal currentUser) {
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
    public StoreMemberResponse addMember(Long storeId, StoreMemberUpsertRequest request, UserPrincipal currentUser) {
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
    public StoreMemberResponse updateMember(Long storeId, Long memberId, StoreMemberUpsertRequest request, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);

        StoreMember member = storeMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findActiveStoreRole(member.getUser().getId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member role not found"));

        if (RoleName.OWNER == userRole.getRole().getName() && !member.getUser().getId().equals(currentUser.userId())) {
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
    public void removeMember(Long storeId, Long memberId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);

        StoreMember member = storeMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findActiveStoreRole(member.getUser().getId(), storeId)
                .orElse(null);

        if (userRole != null && RoleName.OWNER == userRole.getRole().getName()) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot remove the OWNER from store");
        }

        LocalDateTime now = LocalDateTime.now();
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
