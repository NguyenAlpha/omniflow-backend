package com.quiktech.backend.service;

import com.quiktech.backend.annotation.Auditable;
import com.quiktech.backend.dto.request.catalog.UnitUpsertRequest;
import com.quiktech.backend.dto.response.catalog.UnitResponse;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.Business;
import com.quiktech.backend.entity.Unit;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.exception.ForbiddenException;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.BusinessRepository;
import com.quiktech.backend.repository.ProductRepository;
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
    private final ProductRepository productRepository;
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

        // Scoped theo businessId (cho phép unit hệ thống) để chống IDOR — unit của business khác trả về 404
        Unit unit = unitRepository.findByBusinessIdOrSystemAndPublicId(businessId, publicId)
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

    @Auditable(action = "DELETE_UNIT", entityType = "UNIT")
    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);

        // Scoped theo businessId (cho phép unit hệ thống) để chống IDOR — unit của business khác trả về 404
        Unit unit = unitRepository.findByBusinessIdOrSystemAndPublicId(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.UNIT_NOT_FOUND, "Unit not found"));

        if (unit.getBusiness() == null) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Cannot delete system units");
        }

        // Chặn xóa unit đang được product còn sống tham chiếu — Unit có @SQLRestriction
        // và các query product đều INNER JOIN FETCH p.unit, nên nếu xóa unit thì toàn bộ
        // product dùng unit đó biến mất khỏi API (list/get/search), thậm chí search có thể 500
        // vì lazy load unit đã xóa. Yêu cầu chuyển product sang unit khác trước.
        long inUse = productRepository.countByUnitIdAndDeletedAtIsNull(unit.getId());
        if (inUse > 0) {
            throw new IllegalArgumentException(
                    "Cannot delete unit: " + inUse + " product(s) still reference it");
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
