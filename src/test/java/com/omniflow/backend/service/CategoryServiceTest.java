package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.catalog.CategoryUpsertRequest;
import com.omniflow.backend.dto.response.catalog.CategoryResponse;
import com.omniflow.backend.entity.Category;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.StoreMember;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.CategoryRepository;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private StoreMemberRepository storeMemberRepository;

    @InjectMocks private CategoryService categoryService;

    private User owner;
    private User manager;
    private User staff;
    private Store store;
    private StoreMember ownerMember;
    private StoreMember managerMember;
    private StoreMember staffMember;
    private Category category;

    private UserPrincipal ownerPrincipal;
    private UserPrincipal managerPrincipal;
    private UserPrincipal staffPrincipal;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(1L).username("owner").email("owner@test.com").build();
        manager = User.builder().id(2L).username("manager").email("manager@test.com").build();
        staff = User.builder().id(3L).username("staff").email("staff@test.com").build();

        ownerPrincipal = new UserPrincipal(1L, "owner", List.of());
        managerPrincipal = new UserPrincipal(2L, "manager", List.of());
        staffPrincipal = new UserPrincipal(3L, "staff", List.of());

        store = Store.builder().id(10L).name("Main Store").build();

        ownerMember = StoreMember.builder()
                .id(1L).user(owner).store(store)
                .publicId(UUID.randomUUID()).isActive(true).build();

        managerMember = StoreMember.builder()
                .id(2L).user(manager).store(store)
                .publicId(UUID.randomUUID()).isActive(true).build();

        staffMember = StoreMember.builder()
                .id(3L).user(staff).store(store)
                .publicId(UUID.randomUUID()).isActive(true).build();

        category = Category.builder()
                .id(100L).store(store).name("Beverages").description("Drinks")
                .publicId(UUID.randomUUID()).createdBy(owner).lastModifiedByUser(owner)
                .build();
    }

    @Test
    void list_success_whenMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(categoryRepository.findByStoreId(10L)).thenReturn(List.of(category));

        List<CategoryResponse> result = categoryService.list(10L, ownerPrincipal);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Beverages");
    }

    @Test
    void list_throwsForbidden_whenNotMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.list(10L, staffPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void list_throwsNotFound_whenStoreMissing() {
        when(storeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.list(99L, ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Store not found");
    }

    @Test
    void create_success_whenOwner() {
        CategoryUpsertRequest request = new CategoryUpsertRequest("Snacks", "Salty");
        Category created = Category.builder()
                .id(200L).store(store).name("Snacks").description("Salty")
                .publicId(UUID.randomUUID()).createdBy(owner).lastModifiedByUser(owner)
                .build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(categoryRepository.findByStoreIdAndNameAndDeletedAtIsNull(10L, "Snacks"))
                .thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenReturn(created);

        CategoryResponse response = categoryService.create(10L, request, ownerPrincipal);

        assertThat(response.name()).isEqualTo("Snacks");
        assertThat(response.storeId()).isEqualTo(10L);
    }

    @Test
    void create_throwsDuplicateName() {
        CategoryUpsertRequest request = new CategoryUpsertRequest("Beverages", "Drinks");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(categoryRepository.findByStoreIdAndNameAndDeletedAtIsNull(10L, "Beverages"))
                .thenReturn(Optional.of(category));

        assertThatThrownBy(() -> categoryService.create(10L, request, ownerPrincipal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category name already exists in this store");
    }

    @Test
    void create_throwsForbidden_whenStaff() {
        CategoryUpsertRequest request = new CategoryUpsertRequest("Snacks", "Salty");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> categoryService.create(10L, request, staffPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void update_success_whenManager() {
        CategoryUpsertRequest request = new CategoryUpsertRequest("Hot Drinks", "Tea and coffee");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(2L, 10L))
                .thenReturn(Optional.of(managerMember));
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));
        when(categoryRepository.findByStoreIdAndNameAndDeletedAtIsNull(10L, "Hot Drinks"))
                .thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenReturn(category);

        CategoryResponse response = categoryService.update(10L, category.getPublicId(), request, managerPrincipal);

        assertThat(response.name()).isEqualTo("Hot Drinks");
        assertThat(category.getLastModifiedAt()).isNotNull();
        assertThat(category.getUpdatedAt()).isNotNull();
    }

    @Test
    void update_throwsDuplicateName() {
        CategoryUpsertRequest request = new CategoryUpsertRequest("Beverages", "Drinks");
        Category other = Category.builder()
                .id(101L).store(store).name("Beverages").description("Drinks")
                .publicId(UUID.randomUUID()).createdBy(owner).lastModifiedByUser(owner)
                .build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));
        when(categoryRepository.findByStoreIdAndNameAndDeletedAtIsNull(10L, "Beverages"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> categoryService.update(10L, category.getPublicId(), request, ownerPrincipal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category name already exists in this store");
    }

    @Test
    void update_throwsNotFound_whenMissing() {
        CategoryUpsertRequest request = new CategoryUpsertRequest("Beverages", "Drinks");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.update(10L, category.getPublicId(), request, ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Category not found");
    }

    @Test
    void update_throwsForbidden_whenStaff() {
        CategoryUpsertRequest request = new CategoryUpsertRequest("Snacks", "Salty");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> categoryService.update(10L, category.getPublicId(), request, staffPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void delete_success_whenOwner() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.of(category));

        categoryService.delete(10L, category.getPublicId(), ownerPrincipal);

        verify(categoryRepository).save(category);
        assertThat(category.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_throwsNotFound_whenMissing() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(categoryRepository.findByPublicId(category.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.delete(10L, category.getPublicId(), ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Category not found");
    }

    @Test
    void delete_throwsForbidden_whenStaff() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> categoryService.delete(10L, category.getPublicId(), staffPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }
}
