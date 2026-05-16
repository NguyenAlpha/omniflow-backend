package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.catalog.ProductUpsertRequest;
import com.quiktech.backend.dto.response.catalog.ProductResponse;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.entity.Category;
import com.quiktech.backend.entity.PriceHistory;
import com.quiktech.backend.entity.Product;
import com.quiktech.backend.entity.Store;
import com.quiktech.backend.entity.Unit;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.CategoryRepository;
import com.quiktech.backend.repository.PriceHistoryRepository;
import com.quiktech.backend.repository.ProductRepository;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.UnitRepository;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private UserRepository userRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UnitRepository unitRepository;
    @Mock private PriceHistoryRepository priceHistoryRepository;

    @InjectMocks private ProductService productService;

    private User owner;
    private Store store;
    private Category category;
    private Unit unit;
    private Product product;

    private UserPrincipal ownerPrincipal;
    private UserPrincipal managerPrincipal;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(1L).username("owner").email("owner@test.com").build();

        ownerPrincipal = new UserPrincipal(1L, "owner", List.of());
        managerPrincipal = new UserPrincipal(2L, "manager", List.of());

        store = Store.builder().id(10L).name("Main Store").build();

        category = Category.builder()
                .id(11L).store(store).name("Beverages")
                .publicId(UUID.randomUUID()).createdBy(owner).lastModifiedByUser(owner)
                .build();

        unit = Unit.builder()
                .id(21L).store(store).name("Piece").abbreviation("pc")
                .publicId(UUID.randomUUID()).build();

        product = Product.builder()
                .id(101L).store(store).sku("SKU-1").name("Cola")
                .description("Soft drink")
                .category(category)
                .unit(unit)
                .costPrice(new BigDecimal("10.00"))
                .sellingPrice(new BigDecimal("15.00"))
                .minStockLevel(5)
                .isActive(true)
                .publicId(UUID.randomUUID())
                .build();

        lenient().when(userRepository.getReferenceById(anyLong())).thenReturn(owner);
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    void list_success() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        Object[] row = {product, new BigDecimal("50.00")};
        when(productRepository.findByStoreIdAndIsActiveWithStock(10L, true))
                .thenReturn(Collections.singletonList(row));

        List<ProductResponse> result = productService.list(10L, true, ownerPrincipal);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sku()).isEqualTo("SKU-1");
        assertThat(result.get(0).totalStock()).isEqualByComparingTo("50.00");
    }

    @Test
    void list_throwsNotFound_whenStoreMissing() {
        when(storeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.list(99L, null, ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Store not found");
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_success() {
        var pageable = PageRequest.of(0, 10);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        Object[] row = {product, new BigDecimal("50.00")};
        when(productRepository.searchProductsWithStock(eq(10L), eq("cola"), eq(pageable)))
                .thenReturn(new PageImpl<>(Collections.singletonList(row), pageable, 1));

        PagedResult<ProductResponse> result = productService.search(10L, "cola", pageable, ownerPrincipal);

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    void get_success() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByPublicIdWithStock(product.getPublicId()))
                .thenReturn(Optional.of(new Object[]{product, new BigDecimal("50.00")}));

        ProductResponse response = productService.get(10L, product.getPublicId(), ownerPrincipal);

        assertThat(response.name()).isEqualTo("Cola");
        assertThat(response.totalStock()).isEqualByComparingTo("50.00");
    }

    @Test
    void get_throwsNotFound_whenProductMissing() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByPublicIdWithStock(product.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.get(10L, product.getPublicId(), ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found");
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_success() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2", "Tea", "Green tea",
                category.getPublicId(), unit.getPublicId(),
                new BigDecimal("8.00"), new BigDecimal("12.00"), 10, true
        );
        Product created = Product.builder()
                .id(202L).store(store).sku("SKU-2").name("Tea")
                .description("Green tea").category(category).unit(unit)
                .costPrice(new BigDecimal("8.00")).sellingPrice(new BigDecimal("12.00"))
                .minStockLevel(10).isActive(true).publicId(UUID.randomUUID()).build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-2"))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));
        when(unitRepository.findByPublicId(unit.getPublicId()))
                .thenReturn(Optional.of(unit));
        when(productRepository.save(any(Product.class))).thenReturn(created);

        ProductResponse response = productService.create(10L, request, ownerPrincipal);

        assertThat(response.sku()).isEqualTo("SKU-2");
        assertThat(response.storeId()).isEqualTo(10L);
        assertThat(response.totalStock()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void create_throwsDuplicateSku() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1", "Cola", "Soft drink",
                category.getPublicId(), unit.getPublicId(),
                new BigDecimal("10.00"), new BigDecimal("15.00"), 5, true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-1"))
                .thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.create(10L, request, ownerPrincipal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SKU already exists in this store");
    }

    @Test
    void create_throwsNotFound_whenCategoryMissing() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2", "Tea", "Green tea",
                category.getPublicId(), unit.getPublicId(),
                new BigDecimal("8.00"), new BigDecimal("12.00"), 10, true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-2"))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.create(10L, request, ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Category not found");
    }

    @Test
    void create_throwsNotFound_whenUnitMissing() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2", "Tea", "Green tea",
                null, unit.getPublicId(),
                new BigDecimal("8.00"), new BigDecimal("12.00"), 10, true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-2"))
                .thenReturn(Optional.empty());
        when(unitRepository.findByPublicId(unit.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.create(10L, request, ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Unit not found");
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_success_recordsPriceHistory_whenPriceChanged() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1", "Cola", "Soft drink",
                category.getPublicId(), unit.getPublicId(),
                new BigDecimal("11.00"), new BigDecimal("16.00"), 5, true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-1"))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));
        when(unitRepository.findByPublicId(unit.getPublicId()))
                .thenReturn(Optional.of(unit));
        when(productRepository.save(any(Product.class))).thenReturn(product);
        when(productRepository.sumStockByProductId(product.getId()))
                .thenReturn(new BigDecimal("50.00"));

        ProductResponse response = productService.update(10L, product.getPublicId(), request, managerPrincipal);

        assertThat(response.sku()).isEqualTo("SKU-1");
        assertThat(response.totalStock()).isEqualByComparingTo("50.00");
        verify(priceHistoryRepository).save(any(PriceHistory.class));
    }

    @Test
    void update_success_doesNotRecordPriceHistory_whenUnchanged() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1", "Cola", "Soft drink",
                category.getPublicId(), unit.getPublicId(),
                new BigDecimal("10.00"), new BigDecimal("15.00"), 5, true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-1"))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));
        when(unitRepository.findByPublicId(unit.getPublicId()))
                .thenReturn(Optional.of(unit));
        when(productRepository.save(any(Product.class))).thenReturn(product);
        when(productRepository.sumStockByProductId(product.getId()))
                .thenReturn(new BigDecimal("50.00"));

        productService.update(10L, product.getPublicId(), request, ownerPrincipal);

        verify(priceHistoryRepository, never()).save(any(PriceHistory.class));
    }

    @Test
    void update_throwsDuplicateSku() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2", "Cola", "Soft drink",
                category.getPublicId(), unit.getPublicId(),
                new BigDecimal("10.00"), new BigDecimal("15.00"), 5, true
        );
        Product other = Product.builder()
                .id(102L).store(store).sku("SKU-2").name("Sprite")
                .unit(unit).costPrice(new BigDecimal("9.00")).sellingPrice(new BigDecimal("13.00"))
                .minStockLevel(3).isActive(true).publicId(UUID.randomUUID()).build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-2"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> productService.update(10L, product.getPublicId(), request, ownerPrincipal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SKU already exists in this store");
    }

    @Test
    void update_throwsNotFound_whenProductMissing() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1", "Cola", "Soft drink",
                category.getPublicId(), unit.getPublicId(),
                new BigDecimal("10.00"), new BigDecimal("15.00"), 5, true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.update(10L, product.getPublicId(), request, ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found");
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_success() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));

        productService.delete(10L, product.getPublicId(), ownerPrincipal);

        verify(productRepository).save(product);
        assertThat(product.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_throwsNotFound_whenProductMissing() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.delete(10L, product.getPublicId(), ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found");
    }
}
