package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.catalog.UnitUpsertRequest;
import com.omniflow.backend.dto.response.catalog.UnitResponse;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.StoreMember;
import com.omniflow.backend.entity.Unit;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.repository.UnitRepository;
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
class UnitServiceTest {

    @Mock private UnitRepository unitRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private StoreMemberRepository storeMemberRepository;

    @InjectMocks private UnitService unitService;

    private User owner;
    private User manager;
    private User staff;
    private Store store;
    private StoreMember ownerMember;
    private StoreMember managerMember;
    private StoreMember staffMember;
    private Unit systemUnit;
    private Unit storeUnit;

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

        systemUnit = Unit.builder()
                .id(100L).store(null).name("Piece").abbreviation("pc")
                .publicId(UUID.randomUUID()).build();

        storeUnit = Unit.builder()
                .id(101L).store(store).name("Box").abbreviation("bx")
                .publicId(UUID.randomUUID()).build();
    }

    @Test
    void list_success_whenMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findSystemAndStoreUnits(10L)).thenReturn(List.of(systemUnit, storeUnit));

        List<UnitResponse> result = unitService.list(10L, ownerPrincipal);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(UnitResponse::name)
                .containsExactlyInAnyOrder("Piece", "Box");
    }

    @Test
    void list_throwsForbidden_whenNotMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> unitService.list(10L, staffPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void list_throwsNotFound_whenStoreMissing() {
        when(storeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> unitService.list(99L, ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Store not found");
    }

    @Test
    void create_success_whenOwner() {
        UnitUpsertRequest request = new UnitUpsertRequest("Carton", "ct");
        Unit created = Unit.builder()
                .id(200L).store(store).name("Carton").abbreviation("ct")
                .publicId(UUID.randomUUID()).build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findByStoreIdAndNameAndDeletedAtIsNull(10L, "Carton"))
                .thenReturn(Optional.empty());
        when(unitRepository.save(any(Unit.class))).thenReturn(created);

        UnitResponse response = unitService.create(10L, request, ownerPrincipal);

        assertThat(response.name()).isEqualTo("Carton");
        assertThat(response.storeId()).isEqualTo(10L);
    }

    @Test
    void create_throwsDuplicateName() {
        UnitUpsertRequest request = new UnitUpsertRequest("Box", "bx");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findByStoreIdAndNameAndDeletedAtIsNull(10L, "Box"))
                .thenReturn(Optional.of(storeUnit));

        assertThatThrownBy(() -> unitService.create(10L, request, ownerPrincipal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unit name already exists in this store");
    }

    @Test
    void create_throwsForbidden_whenStaff() {
        UnitUpsertRequest request = new UnitUpsertRequest("Carton", "ct");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> unitService.create(10L, request, staffPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void update_success_whenManager() {
        UnitUpsertRequest request = new UnitUpsertRequest("New Box", "nb");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(2L, 10L))
                .thenReturn(Optional.of(managerMember));
        when(unitRepository.findByPublicId(storeUnit.getPublicId()))
                .thenReturn(Optional.of(storeUnit));
        when(unitRepository.findByStoreIdAndNameAndDeletedAtIsNull(10L, "New Box"))
                .thenReturn(Optional.empty());
        when(unitRepository.save(any(Unit.class))).thenReturn(storeUnit);

        UnitResponse response = unitService.update(10L, storeUnit.getPublicId(), request, managerPrincipal);

        assertThat(response.name()).isEqualTo("New Box");
        assertThat(storeUnit.getLastModifiedAt()).isNotNull();
    }

    @Test
    void update_throwsForbidden_onSystemUnit() {
        UnitUpsertRequest request = new UnitUpsertRequest("Piece", "pc");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findByPublicId(systemUnit.getPublicId()))
                .thenReturn(Optional.of(systemUnit));

        assertThatThrownBy(() -> unitService.update(10L, systemUnit.getPublicId(), request, ownerPrincipal))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot modify system units");
    }

    @Test
    void update_throwsDuplicateName() {
        UnitUpsertRequest request = new UnitUpsertRequest("Box", "bx");
        Unit otherUnit = Unit.builder()
                .id(102L).store(store).name("Box").abbreviation("bx")
                .publicId(UUID.randomUUID()).build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findByPublicId(storeUnit.getPublicId()))
                .thenReturn(Optional.of(storeUnit));
        when(unitRepository.findByStoreIdAndNameAndDeletedAtIsNull(10L, "Box"))
                .thenReturn(Optional.of(otherUnit));

        assertThatThrownBy(() -> unitService.update(10L, storeUnit.getPublicId(), request, ownerPrincipal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unit name already exists in this store");
    }

    @Test
    void update_throwsNotFound_whenMissing() {
        UnitUpsertRequest request = new UnitUpsertRequest("Box", "bx");

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findByPublicId(storeUnit.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> unitService.update(10L, storeUnit.getPublicId(), request, ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Unit not found");
    }

    @Test
    void delete_success_whenOwner() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findByPublicId(storeUnit.getPublicId()))
                .thenReturn(Optional.of(storeUnit));

        unitService.delete(10L, storeUnit.getPublicId(), ownerPrincipal);

        verify(unitRepository).save(storeUnit);
        assertThat(storeUnit.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_throwsForbidden_onSystemUnit() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findByPublicId(systemUnit.getPublicId()))
                .thenReturn(Optional.of(systemUnit));

        assertThatThrownBy(() -> unitService.delete(10L, systemUnit.getPublicId(), ownerPrincipal))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot delete system units");
    }

    @Test
    void delete_throwsForbidden_whenStaff() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> unitService.delete(10L, storeUnit.getPublicId(), staffPrincipal))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void delete_throwsNotFound_whenMissing() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(unitRepository.findByPublicId(storeUnit.getPublicId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> unitService.delete(10L, storeUnit.getPublicId(), ownerPrincipal))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Unit not found");
    }
}
