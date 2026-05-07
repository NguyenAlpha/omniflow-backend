package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.catalog.ProductUpsertRequest;
import com.omniflow.backend.dto.response.catalog.ProductResponse;
import com.omniflow.backend.dto.response.common.PagedResult;
import com.omniflow.backend.entity.Category;
import com.omniflow.backend.entity.PriceHistory;
import com.omniflow.backend.entity.Product;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.StoreMember;
import com.omniflow.backend.entity.Unit;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.enums.StoreRole;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.CategoryRepository;
import com.omniflow.backend.repository.PriceHistoryRepository;
import com.omniflow.backend.repository.ProductRepository;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.repository.UnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private StoreMemberRepository storeMemberRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UnitRepository unitRepository;
    @Mock private PriceHistoryRepository priceHistoryRepository;

    @InjectMocks private ProductService productService;

    private User owner;
    private User manager;
    private User staff;
    private Store store;
    private StoreMember ownerMember;
    private StoreMember managerMember;
    private StoreMember staffMember;
    private Category category;
    private Unit unit;
    private Product product;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(1L).username("owner").email("owner@test.com").build();
        manager = User.builder().id(2L).username("manager").email("manager@test.com").build();
        staff = User.builder().id(3L).username("staff").email("staff@test.com").build();

        store = Store.builder().id(10L).name("Main Store").build();

        ownerMember = StoreMember.builder()
                .id(1L).user(owner).store(store).role(StoreRole.OWNER)
                .publicId(UUID.randomUUID()).isActive(true).build();

        managerMember = StoreMember.builder()
                .id(2L).user(manager).store(store).role(StoreRole.MANAGER)
                .publicId(UUID.randomUUID()).isActive(true).build();

        staffMember = StoreMember.builder()
                .id(3L).user(staff).store(store).role(StoreRole.STAFF)
                .publicId(UUID.randomUUID()).isActive(true).build();

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
    }

    @Test
    void list_success_whenMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByStoreIdAndIsActiveAndDeletedAtIsNull(10L, true))
                .thenReturn(List.of(product));

        List<ProductResponse> result = productService.list(10L, true, owner);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sku()).isEqualTo("SKU-1");
    }

    @Test
    void list_throwsForbidden_whenNotMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.list(10L, null, staff))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void list_throwsNotFound_whenStoreMissing() {
        when(storeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.list(99L, null, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Store not found");
    }

    @Test
    void search_success_whenMember() {
        var pageable = PageRequest.of(0, 10);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.searchProducts(eq(10L), eq("cola"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(product), pageable, 1));

        PagedResult<ProductResponse> result = productService.search(10L, "cola", pageable, owner);

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void get_success_whenMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));

        ProductResponse response = productService.get(10L, product.getPublicId(), owner);

        assertThat(response.name()).isEqualTo("Cola");
    }

    @Test
    void get_throwsNotFound_whenProductMissing() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.get(10L, product.getPublicId(), owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found");
    }

    @Test
    void create_success_whenOwner() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2",
                "Tea",
                "Green tea",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("8.00"),
                new BigDecimal("12.00"),
                10,
                true
        );
        Product created = Product.builder()
                .id(202L).store(store).sku("SKU-2").name("Tea")
                .description("Green tea")
                .category(category)
                .unit(unit)
                .costPrice(new BigDecimal("8.00"))
                .sellingPrice(new BigDecimal("12.00"))
                .minStockLevel(10)
                .isActive(true)
                .publicId(UUID.randomUUID())
                .build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-2"))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));
        when(unitRepository.findByPublicId(unit.getPublicId()))
                .thenReturn(Optional.of(unit));
        when(productRepository.save(any(Product.class))).thenReturn(created);

        ProductResponse response = productService.create(10L, request, owner);

        assertThat(response.sku()).isEqualTo("SKU-2");
        assertThat(response.storeId()).isEqualTo(10L);
    }

    @Test
    void create_throwsDuplicateSku() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1",
                "Cola",
                "Soft drink",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("10.00"),
                new BigDecimal("15.00"),
                5,
                true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-1"))
                .thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.create(10L, request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SKU already exists in this store");
    }

    @Test
    void create_throwsForbidden_whenStaff() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2",
                "Tea",
                "Green tea",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("8.00"),
                new BigDecimal("12.00"),
                10,
                true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> productService.create(10L, request, staff))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void create_throwsNotFound_whenCategoryMissing() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2",
                "Tea",
                "Green tea",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("8.00"),
                new BigDecimal("12.00"),
                10,
                true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-2"))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.create(10L, request, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Category not found");
    }

    @Test
    void create_throwsNotFound_whenUnitMissing() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2",
                "Tea",
                "Green tea",
                null,
                unit.getPublicId(),
                new BigDecimal("8.00"),
                new BigDecimal("12.00"),
                10,
                true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-2"))
                .thenReturn(Optional.empty());
        when(unitRepository.findByPublicId(unit.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.create(10L, request, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Unit not found");
    }

    @Test
    void update_success_recordsPriceHistory_whenPriceChanged() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1",
                "Cola",
                "Soft drink",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("11.00"),
                new BigDecimal("16.00"),
                5,
                true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(2L, 10L))
                .thenReturn(Optional.of(managerMember));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-1"))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));
        when(unitRepository.findByPublicId(unit.getPublicId()))
                .thenReturn(Optional.of(unit));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        ProductResponse response = productService.update(10L, product.getPublicId(), request, manager);

        assertThat(response.sku()).isEqualTo("SKU-1");
        verify(priceHistoryRepository).save(any(PriceHistory.class));
    }

    @Test
    void update_success_doesNotRecordPriceHistory_whenUnchanged() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1",
                "Cola",
                "Soft drink",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("10.00"),
                new BigDecimal("15.00"),
                5,
                true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-1"))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));
        when(unitRepository.findByPublicId(unit.getPublicId()))
                .thenReturn(Optional.of(unit));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        productService.update(10L, product.getPublicId(), request, owner);

        verify(priceHistoryRepository, never()).save(any(PriceHistory.class));
    }

    @Test
    void update_throwsDuplicateSku() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-2",
                "Cola",
                "Soft drink",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("10.00"),
                new BigDecimal("15.00"),
                5,
                true
        );
        Product other = Product.builder()
                .id(102L).store(store).sku("SKU-2").name("Sprite")
                .unit(unit)
                .costPrice(new BigDecimal("9.00"))
                .sellingPrice(new BigDecimal("13.00"))
                .minStockLevel(3)
                .isActive(true)
                .publicId(UUID.randomUUID())
                .build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));
        when(productRepository.findByStoreIdAndSkuAndDeletedAtIsNull(10L, "SKU-2"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> productService.update(10L, product.getPublicId(), request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SKU already exists in this store");
    }

    @Test
    void update_throwsNotFound_whenProductMissing() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1",
                "Cola",
                "Soft drink",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("10.00"),
                new BigDecimal("15.00"),
                5,
                true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.update(10L, product.getPublicId(), request, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found");
    }

    @Test
    void update_throwsForbidden_whenStaff() {
        ProductUpsertRequest request = new ProductUpsertRequest(
                "SKU-1",
                "Cola",
                "Soft drink",
                category.getPublicId(),
                unit.getPublicId(),
                new BigDecimal("10.00"),
                new BigDecimal("15.00"),
                5,
                true
        );

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> productService.update(10L, product.getPublicId(), request, staff))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void delete_success_whenOwner() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.of(product));

        productService.delete(10L, product.getPublicId(), owner);

        verify(productRepository).save(product);
        assertThat(product.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_throwsNotFound_whenProductMissing() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(productRepository.findByPublicId(product.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.delete(10L, product.getPublicId(), owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Product not found");
    }

    @Test
    void delete_throwsForbidden_whenStaff() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> productService.delete(10L, product.getPublicId(), staff))
                .isInstanceOf(ForbiddenException.class);
    }
}

