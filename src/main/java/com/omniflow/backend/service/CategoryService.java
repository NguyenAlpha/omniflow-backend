package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.catalog.CategoryUpsertRequest;
import com.omniflow.backend.dto.response.catalog.CategoryResponse;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.entity.Category;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.CategoryRepository;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.repository.UserRepository;
import com.omniflow.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
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
        category.setLastModifiedAt(LocalDateTime.now());
        category.setUpdatedAt(LocalDateTime.now());

        return toResponse(categoryRepository.save(category));
    }

    @Transactional
    public void delete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);

        Category category = categoryRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));

        category.setDeletedAt(LocalDateTime.now());
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
