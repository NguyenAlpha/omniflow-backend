package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.store.StoreCreateRequest;
import com.omniflow.backend.dto.request.store.StoreMemberUpsertRequest;
import com.omniflow.backend.dto.response.store.StoreMemberResponse;
import com.omniflow.backend.dto.response.store.StoreResponse;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.StoreMember;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.enums.StoreRole;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final UserRepository userRepository;

    @Transactional
    public StoreResponse createStore(StoreCreateRequest request, User currentUser) {
        Store store = Store.builder()
                .name(request.name())
                .address(request.address())
                .phone(request.phone())
                .email(request.email())
                .build();
        storeRepository.save(store);

        StoreMember owner = StoreMember.builder()
                .user(currentUser)
                .store(store)
                .role(StoreRole.OWNER)
                .isActive(true)
                .publicId(UUID.randomUUID())
                .build();
        storeMemberRepository.save(owner);

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
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

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
        return storeMemberRepository.findByStoreIdAndIsActiveAndDeletedAtIsNull(storeId, true)
                .stream()
                .map(this::toMemberResponse)
                .toList();
    }

    @Transactional
    public StoreMemberResponse addMember(Long storeId, StoreMemberUpsertRequest request, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER);

        if (storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(request.userId(), storeId).isPresent()) {
            throw new IllegalArgumentException("User is already a member of this store");
        }

        User targetUser = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.USER_NOT_FOUND, "User not found"));

        Store store = findStoreOrThrow(storeId);
        StoreMember member = StoreMember.builder()
                .user(targetUser)
                .store(store)
                .role(request.role())
                .positionTitle(request.positionTitle())
                .isActive(request.isActive())
                .publicId(UUID.randomUUID())
                .build();

        return toMemberResponse(storeMemberRepository.save(member));
    }

    @Transactional
    public StoreMemberResponse updateMember(Long storeId, Long memberId, StoreMemberUpsertRequest request, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER);

        StoreMember member = storeMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        if (StoreRole.OWNER == member.getRole() && !member.getUser().getId().equals(currentUser.getId())) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot modify another OWNER");
        }

        member.setRole(request.role());
        member.setPositionTitle(request.positionTitle());
        member.setIsActive(request.isActive());

        return toMemberResponse(storeMemberRepository.save(member));
    }

    @Transactional
    public void removeMember(Long storeId, Long memberId, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER);

        StoreMember member = storeMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_MEMBER_NOT_FOUND, "Member not found"));

        if (StoreRole.OWNER == member.getRole()) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot remove the OWNER from store");
        }

        member.setDeletedAt(java.time.LocalDateTime.now());
        storeMemberRepository.save(member);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private void requireMembership(Long storeId, Long userId) {
        if (isSystemAdmin()) return;
        storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(userId, storeId)
                .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "You are not a member of this store"));
    }

    private void requireRole(Long storeId, Long userId, StoreRole... roles) {
        if (isSystemAdmin()) return;
        StoreMember member = storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(userId, storeId)
                .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "You are not a member of this store"));

        for (StoreRole role : roles) {
            if (role == member.getRole()) return;
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
                store.getId(),
                store.getName(),
                store.getAddress(),
                store.getPhone(),
                store.getEmail(),
                store.getIsActive(),
                store.getCreatedAt(),
                store.getUpdatedAt()
        );
    }

    private StoreMemberResponse toMemberResponse(StoreMember m) {
        return new StoreMemberResponse(
                m.getId(),
                m.getPublicId(),
                m.getUser().getId(),
                m.getUser().getUsername(),
                m.getStore().getId(),
                m.getRole(),
                m.getPositionTitle(),
                m.getJoinedDate(),
                m.getIsActive(),
                m.getSyncVersion(),
                m.getLastModifiedAt()
        );
    }
}
