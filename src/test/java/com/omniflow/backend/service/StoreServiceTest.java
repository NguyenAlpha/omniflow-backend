package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.store.StoreCreateRequest;
import com.omniflow.backend.dto.request.store.StoreMemberUpsertRequest;
import com.omniflow.backend.dto.response.store.StoreMemberResponse;
import com.omniflow.backend.dto.response.store.StoreResponse;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.StoreMember;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.enums.StoreRole;
import com.omniflow.backend.exception.ForbiddenException;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.StoreMemberRepository;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.repository.UserRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

    @Mock private StoreRepository storeRepository;
    @Mock private StoreMemberRepository storeMemberRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private StoreService storeService;

    private User owner;
    private User manager;
    private User staff;
    private Store store;
    private StoreMember ownerMember;
    private StoreMember managerMember;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(1L).username("owner").email("owner@test.com").build();
        manager = User.builder().id(2L).username("manager").email("manager@test.com").build();
        staff = User.builder().id(3L).username("staff").email("staff@test.com").build();

        store = Store.builder().id(10L).name("Test Store").isActive(true).build();

        ownerMember = StoreMember.builder()
                .id(1L).user(owner).store(store).role(StoreRole.OWNER)
                .publicId(UUID.randomUUID()).isActive(true).build();

        managerMember = StoreMember.builder()
                .id(2L).user(manager).store(store).role(StoreRole.MANAGER)
                .publicId(UUID.randomUUID()).isActive(true).build();
    }

    // ── createStore ───────────────────────────────────────────────────────────

    @Test
    void createStore_success() {
        StoreCreateRequest request = new StoreCreateRequest("My Store", "123 Street", "0901234567", "store@test.com");
        when(storeRepository.save(any())).thenReturn(store);
        when(storeMemberRepository.save(any())).thenReturn(ownerMember);

        StoreResponse response = storeService.createStore(request, owner);

        assertThat(response.name()).isEqualTo("My Store");
        verify(storeRepository).save(any(Store.class));
        verify(storeMemberRepository).save(any(StoreMember.class));
    }

    // ── getStore ──────────────────────────────────────────────────────────────

    @Test
    void getStore_success_whenMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));

        StoreResponse response = storeService.getStore(10L, owner);

        assertThat(response.id()).isEqualTo(10L);
    }

    @Test
    void getStore_throwsNotFound_whenStoreNotExist() {
        when(storeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getStore(99L, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Store not found");
    }

    @Test
    void getStore_throwsForbidden_whenNotMember() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getStore(10L, staff))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── getMyStores ───────────────────────────────────────────────────────────

    @Test
    void getMyStores_returnsAllStoresForUser() {
        when(storeMemberRepository.findByUserIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(ownerMember));

        List<StoreResponse> result = storeService.getMyStores(owner);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(10L);
    }

    // ── updateStore ───────────────────────────────────────────────────────────

    @Test
    void updateStore_success_whenOwner() {
        StoreCreateRequest request = new StoreCreateRequest("Updated Store", null, null, null);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(storeRepository.save(any())).thenReturn(store);

        StoreResponse response = storeService.updateStore(10L, request, owner);

        verify(storeRepository).save(store);
    }

    @Test
    void updateStore_success_whenManager() {
        StoreCreateRequest request = new StoreCreateRequest("Updated Store", null, null, null);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(2L, 10L))
                .thenReturn(Optional.of(managerMember));
        when(storeRepository.save(any())).thenReturn(store);

        storeService.updateStore(10L, request, manager);

        verify(storeRepository).save(store);
    }

    @Test
    void updateStore_throwsForbidden_whenStaff() {
        StoreMember staffMember = StoreMember.builder()
                .id(3L).user(staff).store(store).role(StoreRole.STAFF)
                .publicId(UUID.randomUUID()).isActive(true).build();

        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(3L, 10L))
                .thenReturn(Optional.of(staffMember));

        assertThatThrownBy(() -> storeService.updateStore(10L, new StoreCreateRequest("X", null, null, null), staff))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── addMember ─────────────────────────────────────────────────────────────

    @Test
    void addMember_success_whenOwner() {
        StoreMemberUpsertRequest request = new StoreMemberUpsertRequest(2L, StoreRole.MANAGER, "Sales", true);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(2L, 10L))
                .thenReturn(Optional.empty());
        when(userRepository.findById(2L)).thenReturn(Optional.of(manager));
        when(storeMemberRepository.save(any())).thenReturn(managerMember);

        StoreMemberResponse response = storeService.addMember(10L, request, owner);

        assertThat(response.role()).isEqualTo(StoreRole.MANAGER);
        verify(storeMemberRepository).save(any(StoreMember.class));
    }

    @Test
    void addMember_throwsWhenAlreadyMember() {
        StoreMemberUpsertRequest request = new StoreMemberUpsertRequest(2L, StoreRole.MANAGER, null, true);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(2L, 10L))
                .thenReturn(Optional.of(managerMember));

        assertThatThrownBy(() -> storeService.addMember(10L, request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User is already a member of this store");
    }

    @Test
    void addMember_throwsForbidden_whenNotOwner() {
        StoreMemberUpsertRequest request = new StoreMemberUpsertRequest(3L, StoreRole.STAFF, null, true);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(2L, 10L))
                .thenReturn(Optional.of(managerMember));

        assertThatThrownBy(() -> storeService.addMember(10L, request, manager))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── removeMember ──────────────────────────────────────────────────────────

    @Test
    void removeMember_success_whenRemovingNonOwner() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(storeMemberRepository.findById(2L)).thenReturn(Optional.of(managerMember));

        storeService.removeMember(10L, 2L, owner);

        verify(storeMemberRepository).save(managerMember);
        assertThat(managerMember.getDeletedAt()).isNotNull();
    }

    @Test
    void removeMember_throwsForbidden_whenRemovingOwner() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByUserIdAndStoreIdAndDeletedAtIsNull(1L, 10L))
                .thenReturn(Optional.of(ownerMember));
        when(storeMemberRepository.findById(1L)).thenReturn(Optional.of(ownerMember));

        assertThatThrownBy(() -> storeService.removeMember(10L, 1L, owner))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot remove the OWNER from store");
    }
}
