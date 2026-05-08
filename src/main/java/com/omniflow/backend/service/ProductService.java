package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.catalog.ProductUpsertRequest;
import com.omniflow.backend.dto.response.catalog.ProductResponse;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.dto.response.common.PagedResult;
import com.omniflow.backend.entity.*;
import com.omniflow.backend.entity.enums.StoreRole;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    @Transactional(readOnly = true)
    public List<ProductResponse> list(Long storeId, Boolean isActive, User currentUser) {
        findStoreOrThrow(storeId);
        requireMembership(storeId, currentUser.getId());

        List<Product> products = isActive != null
                ? productRepository.findByStoreIdAndIsActiveAndDeletedAtIsNull(storeId, isActive)
                : productRepository.findByStoreIdAndDeletedAtIsNull(storeId);

        return products.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductResponse> search(Long storeId, String searchTerm, Pageable pageable, User currentUser) {
        findStoreOrThrow(storeId);
        requireMembership(storeId, currentUser.getId());
        return PagedResult.of(
                productRepository.searchProducts(storeId, searchTerm, pageable).map(this::toResponse)
        );
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long storeId, UUID publicId, User currentUser) {
        findStoreOrThrow(storeId);
        requireMembership(storeId, currentUser.getId());
        return toResponse(findProductOrThrow(publicId));
    }

    @Transactional
    public ProductResponse create(Long storeId, ProductUpsertRequest request, User currentUser) {
        Store store = findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        if (productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(storeId, request.sku()).isPresent()) {
            throw new IllegalArgumentException("SKU already exists in this store");
        }

        Category category = resolveCategory(request.categoryPublicId());
        Unit unit = resolveUnit(request.unitPublicId());

        Product product = Product.builder()
                .store(store)
                .sku(request.sku())
                .name(request.name())
                .description(request.description())
                .category(category)
                .unit(unit)
                .costPrice(request.costPrice())
                .sellingPrice(request.sellingPrice())
                .minStockLevel(request.minStockLevel())
                .isActive(request.isActive())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(currentUser)
                .build();

        return toResponse(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long storeId, UUID publicId, ProductUpsertRequest request, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        Product product = findProductOrThrow(publicId);

        productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(storeId, request.sku())
                .filter(p -> !p.getPublicId().equals(publicId))
                .ifPresent(p -> { throw new IllegalArgumentException("SKU already exists in this store"); });

        recordPriceHistoryIfChanged(product, request.costPrice(), request.sellingPrice(), currentUser);

        product.setSku(request.sku());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setCategory(resolveCategory(request.categoryPublicId()));
        product.setUnit(resolveUnit(request.unitPublicId()));
        product.setCostPrice(request.costPrice());
        product.setSellingPrice(request.sellingPrice());
        product.setMinStockLevel(request.minStockLevel());
        product.setIsActive(request.isActive());
        product.setLastModifiedByUser(currentUser);
        product.setLastModifiedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());

        return toResponse(productRepository.save(product));
    }

    @Transactional
    public void delete(Long storeId, UUID publicId, User currentUser) {
        findStoreOrThrow(storeId);
        requireRole(storeId, currentUser.getId(), StoreRole.OWNER, StoreRole.MANAGER);

        Product product = findProductOrThrow(publicId);
        product.setDeletedAt(LocalDateTime.now());
        productRepository.save(product);
    }

    private void recordPriceHistoryIfChanged(Product product, BigDecimal newCostPrice, BigDecimal newSellingPrice, User changedBy) {
        boolean costChanged = product.getCostPrice().compareTo(newCostPrice) != 0;
        boolean sellingChanged = product.getSellingPrice().compareTo(newSellingPrice) != 0;
        if (!costChanged && !sellingChanged) return;

        PriceHistory history = PriceHistory.builder()
                .store(product.getStore())
                .product(product)
                .oldCostPrice(product.getCostPrice())
                .newCostPrice(newCostPrice)
                .oldSellingPrice(product.getSellingPrice())
                .newSellingPrice(newSellingPrice)
                .changedBy(changedBy)
                .build();
        priceHistoryRepository.save(history);
    }

    private Category resolveCategory(UUID publicId) {
        if (publicId == null) return null;
        return categoryRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found"));
    }

    private Unit resolveUnit(UUID publicId) {
        return unitRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.UNIT_NOT_FOUND, "Unit not found"));
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private Product findProductOrThrow(UUID publicId) {
        return productRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));
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

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(
                p.getId(), p.getPublicId(), p.getStore().getId(),
                p.getSku(), p.getName(), p.getDescription(),
                p.getCategory() != null ? p.getCategory().getId() : null,
                p.getUnit().getId(),
                p.getCostPrice(), p.getSellingPrice(),
                p.getMinStockLevel(), p.getIsActive(),
                p.getSyncVersion(), p.getLastModifiedAt(),
                p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
