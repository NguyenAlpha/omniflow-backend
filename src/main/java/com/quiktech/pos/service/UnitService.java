package com.quiktech.pos.service;

import com.quiktech.pos.annotation.Auditable;
import com.quiktech.pos.dto.request.catalog.UnitUpsertRequest;
import com.quiktech.pos.dto.response.catalog.UnitResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.Unit;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.exception.ForbiddenException;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.BusinessRepository;
import com.quiktech.pos.repository.ProductRepository;
import com.quiktech.pos.repository.UnitRepository;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.security.UserPrincipal;
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

        // Trim trước khi check unique + lưu: không trim thì " Cái" và "Cái" cùng tồn tại được
        String name = request.name().trim();

        if (unitRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, name).isPresent()) {
            throw new IllegalArgumentException("Unit name already exists in this business");
        }
        // Check cả system unit: DB không chặn (ux_units_business_name dùng COALESCE(business_id, 0)
        // nên business unit trùng tên system unit vẫn insert được) → dropdown hiện 2 mục "Cái"
        if (unitRepository.findByBusinessIdIsNullAndNameAndDeletedAtIsNull(name).isPresent()) {
            throw new IllegalArgumentException("Unit name conflicts with a system unit");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Unit unit = Unit.builder()
                .business(business)
                .name(name)
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

        // Trim + check trùng system unit như create (xem chú thích ở create)
        String name = request.name().trim();

        unitRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, name)
                .filter(u -> !u.getPublicId().equals(publicId))
                .ifPresent(u -> { throw new IllegalArgumentException("Unit name already exists in this business"); });
        if (unitRepository.findByBusinessIdIsNullAndNameAndDeletedAtIsNull(name).isPresent()) {
            throw new IllegalArgumentException("Unit name conflicts with a system unit");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        unit.setName(name);
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
        // Soft delete cũng là mutation — set trường sync để client local-first nhận được
        // tín hiệu "record đã xóa" khi sync delta được implement
        unit.setLastModifiedByUser(userRepository.getReferenceById(currentUser.userId()));
        unit.setLastModifiedAt(Instant.now());
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
