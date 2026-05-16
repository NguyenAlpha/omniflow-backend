package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.catalog.CategoryUpsertRequest;
import com.quiktech.backend.dto.response.catalog.CategoryResponse;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.Category;
import com.quiktech.backend.entity.Store;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.CategoryRepository;
import com.quiktech.backend.repository.StoreRepository;
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
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<CategoryResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return categoryRepository.findByStoreId(storeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CategoryResponse create(Long storeId, CategoryUpsertRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (categoryRepository.findByStoreIdAndNameAndDeletedAtIsNull(storeId, request.name()).isPresent()) {
            throw new IllegalArgumentException("Category name already exists in this store");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Category category = Category.builder()
                .store(store)
                .name(request.name())
                .description(request.description())
                .publicId(UUID.randomUUID())
                .createdBy(userRef)
                .lastModifiedByUser(userRef)
                .build();

        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long storeId, UUID publicId, CategoryUpsertRequest request, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);

        Category category = categoryRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));

        categoryRepository.findByStoreIdAndNameAndDeletedAtIsNull(storeId, request.name())
                .filter(c -> !c.getPublicId().equals(publicId))
                .ifPresent(c -> { throw new IllegalArgumentException("Category name already exists in this store"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());

        category.setName(request.name());
        category.setDescription(request.description());
        category.setLastModifiedByUser(userRef);
        category.setLastModifiedAt(Instant.now());
        category.setUpdatedAt(Instant.now());

        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);

        Category category = categoryRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));

        category.setDeletedAt(Instant.now());
        categoryRepository.save(category);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
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
