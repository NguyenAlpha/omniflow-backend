package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.catalog.UnitUpsertRequest;
import com.omniflow.backend.dto.response.catalog.UnitResponse;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.Unit;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.enums.StoreRole;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.repository.UnitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnitService {

    private final UnitRepository unitRepository;
    private final StoreRepository storeRepository;
    private final StoreMemberRepository storeMemberRepository;

    @Transactional(readOnly = true)
    public List<UnitResponse> list(Long storeId, User currentUser) {
        findStoreOrThrow(storeId);
        requireMembership(storeId, currentUser.getId());
        return unitRepository.findSystemAndStoreUnits(storeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public UnitResponse create(Long storeId, UnitUpsertRequest request, User currentUser) {
        Store store = findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        if (unitRepository.findByStoreIdAndNameAndDeletedAtIsNull(storeId, request.name()).isPresent()) {
            throw new IllegalArgumentException("Unit name already exists in this store");
        }

        Unit unit = Unit.builder()
                .store(store)
                .name(request.name())
                .abbreviation(request.abbreviation())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(currentUser)
                .build();

        return toResponse(unitRepository.save(unit));
    }

    @Transactional
    public UnitResponse update(Long storeId, UUID publicId, UnitUpsertRequest request, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        Unit unit = unitRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.UNIT_NOT_FOUND, "Unit not found"));

        if (unit.getStore() == null) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot modify system units");
        }

        unitRepository.findByStoreIdAndNameAndDeletedAtIsNull(storeId, request.name())
                .filter(u -> !u.getPublicId().equals(publicId))
                .ifPresent(u -> { throw new IllegalArgumentException("Unit name already exists in this store"); });

        unit.setName(request.name());
        unit.setAbbreviation(request.abbreviation());
        unit.setLastModifiedByUser(currentUser);
        unit.setLastModifiedAt(LocalDateTime.now());

        return toResponse(unitRepository.save(unit));
    }

    @Transactional
    public void delete(Long storeId, UUID publicId, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        Unit unit = unitRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.UNIT_NOT_FOUND, "Unit not found"));

        if (unit.getStore() == null) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot delete system units");
        }

        unit.setDeletedAt(LocalDateTime.now());
        unitRepository.save(unit);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private void requireMembership(Long storeId, Long userId) {
        storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(userId, storeId)
                .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "You are not a member of this store"));
    }

    private void requireRole(Long storeId, Long userId, StoreRole... roles) {
        var member = storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(userId, storeId)
                .orElseThrow(() -> new ForbiddenException(ErrorCode.FORBIDDEN, "You are not a member of this store"));
        for (StoreRole role : roles) {
            if (role == member.getRole()) return;
        }
        throw new ForbiddenException(ErrorCode.FORBIDDEN, "Insufficient role to perform this action");
    }

    private UnitResponse toResponse(Unit u) {
        return new UnitResponse(
                u.getId(), u.getPublicId(),
                u.getStore() != null ? u.getStore().getId() : null,
                u.getName(), u.getAbbreviation(),
                u.getSyncVersion(), u.getLastModifiedAt()
        );
    }
}
