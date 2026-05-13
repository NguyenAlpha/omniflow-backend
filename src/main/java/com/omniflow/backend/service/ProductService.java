package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.catalog.ProductUpsertRequest;
import com.omniflow.backend.dto.response.catalog.ProductResponse;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.dto.response.common.PagedResult;
import com.omniflow.backend.entity.*;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.*;
import com.omniflow.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    @Transactional(readOnly = true)
    public List<ProductResponse> list(Long storeId, Boolean isActive, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        List<Product> products = isActive != null
                ? productRepository.findByStoreIdAndIsActiveAndDeletedAtIsNull(storeId, isActive)
                : productRepository.findByStoreIdAndDeletedAtIsNull(storeId);
        return products.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResult<ProductResponse> search(Long storeId, String searchTerm, Pageable pageable, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return PagedResult.of(
                productRepository.searchProducts(storeId, searchTerm, pageable).map(this::toResponse)
        );
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return toResponse(findProductOrThrow(publicId));
    }

    @Transactional
    public ProductResponse create(Long storeId, ProductUpsertRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(storeId, request.sku()).isPresent()) {
            throw new IllegalArgumentException("SKU already exists in this store");
        }

        Category category = resolveCategory(request.categoryPublicId());
        Unit unit = resolveUnit(request.unitPublicId());

        // getReferenceById: JPA proxy — không SELECT, chỉ dùng ID cho FK lastModifiedByUser
        User userRef = userRepository.getReferenceById(currentUser.userId());

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
                .lastModifiedByUser(userRef)
                .build();

        return toResponse(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long storeId, UUID publicId, ProductUpsertRequest request, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);

        Product product = findProductOrThrow(publicId);

        productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(storeId, request.sku())
                .filter(p -> !p.getPublicId().equals(publicId))
                .ifPresent(p -> { throw new IllegalArgumentException("SKU already exists in this store"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());
        recordPriceHistoryIfChanged(product, request.costPrice(), request.sellingPrice(), userRef);

        product.setSku(request.sku());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setCategory(resolveCategory(request.categoryPublicId()));
        product.setUnit(resolveUnit(request.unitPublicId()));
        product.setCostPrice(request.costPrice());
        product.setSellingPrice(request.sellingPrice());
        product.setMinStockLevel(request.minStockLevel());
        product.setIsActive(request.isActive());
        product.setLastModifiedByUser(userRef);
        product.setLastModifiedAt(Instant.now());
        product.setUpdatedAt(Instant.now());

        return toResponse(productRepository.save(product));
    }

    @Transactional
    public void delete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Product product = findProductOrThrow(publicId);
        product.setDeletedAt(Instant.now());
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
