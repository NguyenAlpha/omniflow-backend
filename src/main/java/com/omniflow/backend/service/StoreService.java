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
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    @Transactional
    public StoreResponse createStore(StoreCreateRequest request, User currentUser) {
        Store store = Store.builder()
                .name(request.name())
                .address(request.address())
                .phone(request.phone())
                .email(request.email())
                .build();
        storeRepository.save(store);

        StoreMember member = StoreMember.builder()
                .user(currentUser)
                .store(store)
                .isActive(true)
                .publicId(UUID.randomUUID())
                .build();
        storeMemberRepository.save(member);

        userRoleRepository.save(UserRole.builder()
                .user(currentUser)
                .role(findRoleOrThrow(RoleName.OWNER))
                .store(store)
                .isActive(true)
                .build());

        return toStoreResponse(store);
    }

    @Transactional(readOnly = true)
    public StoreResponse getStore(Long storeId, User currentUser) {
        Store store = findStoreOrThrow(storeId);
        requireMembership(storeId, currentUser.getId());
        return toStoreResponse(store);
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> getMyStores(User currentUser) {
        return storeMemberRepository.findByUserIdAndDeletedAtIsNull(currentUser.getId())
                .stream()
                .map(m -> toStoreResponse(m.getStore()))
                .toList();
    }

    @Transactional
    public StoreResponse updateStore(Long storeId, StoreCreateRequest request, User currentUser) {
        Store store = findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), RoleName.OWNER, RoleName.MANAGER);

        store.setName(request.name());
        store.setAddress(request.address());
        store.setPhone(request.phone());
        store.setEmail(request.email());

        return toStoreResponse(storeRepository.save(store));
    }

    @Transactional(readOnly = true)
    public List<StoreMemberResponse> getMembers(Long storeId, User currentUser) {
        findStoreOrThrow(storeId);
        requireMembership(storeId, currentUser.getId());

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
    public StoreMemberResponse addMember(Long storeId, StoreMemberUpsertRequest request, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), RoleName.OWNER);

        if (userRoleRepository.findByUserIdAndStoreIdAndIsActiveTrueAndDeletedAtIsNull(request.userId(), storeId).isPresent()) {
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

        return toMemberResponse(member, userRole);
    }

    @Transactional
    public StoreMemberResponse updateMember(Long storeId, Long memberId, StoreMemberUpsertRequest request, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), RoleName.OWNER);

        StoreMember member = storeMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findByUserIdAndStoreIdAndIsActiveTrueAndDeletedAtIsNull(member.getUser().getId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member role not found"));

        if (RoleName.OWNER == userRole.getRole().getName() && !member.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot modify another OWNER");
        }

        userRole.setRole(findRoleOrThrow(request.role()));
        userRole.setIsActive(request.isActive());
        userRoleRepository.save(userRole);

        member.setPositionTitle(request.positionTitle());
        member.setIsActive(request.isActive());
        storeMemberRepository.save(member);

        return toMemberResponse(member, userRole);
    }

    @Transactional
    public void removeMember(Long storeId, Long memberId, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), RoleName.OWNER);

        StoreMember member = storeMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        UserRole userRole = userRoleRepository
                .findByUserIdAndStoreIdAndIsActiveTrueAndDeletedAtIsNull(member.getUser().getId(), storeId)
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
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private Role findRoleOrThrow(RoleName roleName) {
        return roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role not found: " + roleName));
    }

    private void requireMembership(Long storeId, Long userId) {
        if (isSystemAdmin()) return;
        userRoleRepository.findByUserIdAndStoreIdAndIsActiveTrueAndDeletedAtIsNull(userId, storeId)
                .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "You are not a member of this store"));
    }

    private void requireRole(Long storeId, Long userId, RoleName... roles) {
        if (isSystemAdmin()) return;
        UserRole userRole = userRoleRepository
                .findByUserIdAndStoreIdAndIsActiveTrueAndDeletedAtIsNull(userId, storeId)
                .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "You are not a member of this store"));

        RoleName actual = userRole.getRole().getName();
        for (RoleName role : roles) {
            if (role == actual) return;
        }
        throw new ForbiddenException(ErrorCode.FORBIDDEN, "Insufficient role to perform this action");
    }

    private boolean isSystemAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
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
