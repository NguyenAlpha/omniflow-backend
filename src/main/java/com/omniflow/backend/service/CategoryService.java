package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.catalog.CategoryUpsertRequest;
import com.omniflow.backend.dto.response.catalog.CategoryResponse;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.entity.Category;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.enums.StoreRole;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.CategoryRepository;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final StoreRepository storeRepository;
    private final StoreMemberRepository storeMemberRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(Long storeId, User currentUser) {
        findStoreOrThrow(storeId);
        requireMembership(storeId, currentUser.getId());
        return categoryRepository.findByStoreId(storeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CategoryResponse create(Long storeId, CategoryUpsertRequest request, User currentUser) {
        Store store = findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        if (categoryRepository.findByStoreIdAndNameAndDeletedAtIsNull(storeId, request.name()).isPresent()) {
            throw new IllegalArgumentException("Category name already exists in this store");
        }

        Category category = Category.builder()
                .store(store)
                .name(request.name())
                .description(request.description())
                .publicId(UUID.randomUUID())
                .createdBy(currentUser)
                .lastModifiedByUser(currentUser)
                .build();

        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long storeId, UUID publicId, CategoryUpsertRequest request, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        Category category = categoryRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));

        categoryRepository.findByStoreIdAndNameAndDeletedAtIsNull(storeId, request.name())
                .filter(c -> !c.getPublicId().equals(publicId))
                .ifPresent(c -> { throw new IllegalArgumentException("Category name already exists in this store"); });

        category.setName(request.name());
        category.setDescription(request.description());
        category.setLastModifiedByUser(currentUser);
        category.setLastModifiedAt(LocalDateTime.now());
        category.setUpdatedAt(LocalDateTime.now());

        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long storeId, UUID publicId, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        Category category = categoryRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));

        category.setDeletedAt(LocalDateTime.now());
        categoryRepository.save(category);
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
        var member = storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(userId, storeId)
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

    private CategoryResponse toResponse(Category c) {
        return new CategoryResponse(
                c.getId(), c.getPublicId(), c.getStore().getId(),
                c.getName(), c.getDescription(),
                c.getSyncVersion(), c.getLastModifiedAt(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
