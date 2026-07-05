package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.catalog.UnitUpsertRequest;
import com.quiktech.backend.dto.response.catalog.UnitResponse;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.Business;
import com.quiktech.backend.entity.Unit;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.exception.ForbiddenException;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.BusinessRepository;
import com.quiktech.backend.repository.UnitRepository;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnitService {

    private final UnitRepository unitRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<UnitResponse> list(Long businessId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return unitRepository.findSystemAndBusinessUnits(businessId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public UnitResponse create(Long businessId, UnitUpsertRequest request, UserPrincipal currentUser) {
        Business business = findBusinessOrThrow(businessId);

        if (unitRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, request.name()).isPresent()) {
            throw new IllegalArgumentException("Unit name already exists in this business");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Unit unit = Unit.builder()
                .business(business)
                .name(request.name())
                .abbreviation(request.abbreviation())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .build();

        return toResponse(unitRepository.save(unit));
    }

    @Transactional
    public UnitResponse update(Long businessId, UUID publicId, UnitUpsertRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);

        Unit unit = unitRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.UNIT_NOT_FOUND, "Unit not found"));

        if (unit.getBusiness() == null) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot modify system units");
        }

        unitRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, request.name())
                .filter(u -> !u.getPublicId().equals(publicId))
                .ifPresent(u -> { throw new IllegalArgumentException("Unit name already exists in this business"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());

        unit.setName(request.name());
        unit.setAbbreviation(request.abbreviation());
        unit.setLastModifiedByUser(userRef);
        unit.setLastModifiedAt(Instant.now());

        return toResponse(unitRepository.save(unit));
    }

    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);

        Unit unit = unitRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.UNIT_NOT_FOUND, "Unit not found"));

        if (unit.getBusiness() == null) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot delete system units");
        }

        unit.setDeletedAt(Instant.now());
        unitRepository.save(unit);
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    private UnitResponse toResponse(Unit u) {
        return new UnitResponse(
                u.getId(), u.getPublicId(),
                u.getBusiness() != null ? u.getBusiness().getId() : null,
                u.getName(), u.getAbbreviation(),
                u.getSyncVersion(), u.getLastModifiedAt()
        );
    }
}
