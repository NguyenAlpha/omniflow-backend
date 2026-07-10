package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.catalog.CategoryUpsertRequest;
import com.quiktech.backend.dto.response.catalog.CategoryResponse;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.Business;
import com.quiktech.backend.entity.Category;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.BusinessRepository;
import com.quiktech.backend.repository.CategoryRepository;
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
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(Long businessId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return categoryRepository.findByBusinessId(businessId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CategoryResponse create(Long businessId, CategoryUpsertRequest request, UserPrincipal currentUser) {
        Business business = findBusinessOrThrow(businessId);

        if (categoryRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, request.name()).isPresent()) {
            throw new IllegalArgumentException("Category name already exists in this business");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Category category = Category.builder()
                .business(business)
                .name(request.name())
                .description(request.description())
                .publicId(UUID.randomUUID())
                .createdBy(userRef)
                .lastModifiedByUser(userRef)
                .build();

        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long businessId, UUID publicId, CategoryUpsertRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);

        // Scoped theo businessId để chống IDOR — category của business khác trả về 404
        Category category = categoryRepository.findByBusinessIdAndPublicId(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));

        categoryRepository.findByBusinessIdAndNameAndDeletedAtIsNull(businessId, request.name())
                .filter(c -> !c.getPublicId().equals(publicId))
                .ifPresent(c -> { throw new IllegalArgumentException("Category name already exists in this business"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());

        category.setName(request.name());
        category.setDescription(request.description());
        category.setLastModifiedByUser(userRef);
        category.setLastModifiedAt(Instant.now());
        category.setUpdatedAt(Instant.now());

        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);

        // Scoped theo businessId để chống IDOR — category của business khác trả về 404
        Category category = categoryRepository.findByBusinessIdAndPublicId(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));

        category.setDeletedAt(Instant.now());
        categoryRepository.save(category);
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    private CategoryResponse toResponse(Category c) {
        return new CategoryResponse(
                c.getId(), c.getPublicId(), c.getBusiness().getId(),
                c.getName(), c.getDescription(),
                c.getSyncVersion(), c.getLastModifiedAt(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
