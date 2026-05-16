package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.store.StoreCreateRequest;
import com.quiktech.backend.dto.request.store.AddMemberRequest;
import com.quiktech.backend.dto.request.store.UpdateMemberRequest;
import com.quiktech.backend.dto.response.store.StoreMemberResponse;
import com.quiktech.backend.dto.response.store.StoreResponse;
import com.quiktech.backend.entity.Role;
import com.quiktech.backend.entity.Store;
import com.quiktech.backend.entity.StoreMember;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.entity.UserRole;
import com.quiktech.backend.entity.enums.RoleName;
import com.quiktech.backend.exception.ForbiddenException;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.RoleRepository;
import com.quiktech.backend.repository.StoreMemberRepository;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.repository.UserRoleRepository;
import com.quiktech.backend.security.StoreAccessEvaluator;
import com.quiktech.backend.security.UserPrincipal;
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
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private StoreAccessEvaluator storeAccessEvaluator;

    @InjectMocks private StoreService storeService;

    private User ownerUser;
    private User managerUser;
    private Store store;
    private Role ownerRole;
    private Role managerRole;
    private StoreMember ownerMember;
    private StoreMember managerMember;
    private UserRole ownerUserRole;
    private UserRole managerUserRole;
    private UserPrincipal ownerPrincipal;
    private UserPrincipal adminPrincipal;

    @BeforeEach
    void setUp() {
        ownerUser = User.builder().id(1L).username("owner").email("owner@test.com").build();
        managerUser = User.builder().id(2L).username("manager").email("manager@test.com").build();

        store = Store.builder().id(10L).name("Test Store").isActive(true).build();

        ownerRole = Role.builder().id(1L).name(RoleName.ROLE_OWNER).build();
        managerRole = Role.builder().id(2L).name(RoleName.ROLE_MANAGER).build();

        ownerMember = StoreMember.builder()
                .id(1L).user(ownerUser).store(store)
                .publicId(UUID.randomUUID()).isActive(true).build();

        managerMember = StoreMember.builder()
                .id(2L).user(managerUser).store(store)
                .publicId(UUID.randomUUID()).isActive(true).build();

        ownerUserRole = UserRole.builder().user(ownerUser).role(ownerRole).store(store).isActive(true).build();
        managerUserRole = UserRole.builder().user(managerUser).role(managerRole).store(store).isActive(true).build();

        ownerPrincipal = new UserPrincipal(1L, "owner", List.of(RoleName.ROLE_OWNER.name()));
        adminPrincipal = new UserPrincipal(99L, "admin", List.of(RoleName.ROLE_SUPER_ADMIN.name()));
    }

    // ── getStores ─────────────────────────────────────────────────────────────

    @Test
    void getStores_returnsAllStores_whenAdmin() {
        when(storeRepository.findAll()).thenReturn(List.of(store));

        List<StoreResponse> result = storeService.getStores(adminPrincipal);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(10L);
        verify(storeMemberRepository, never()).findByUserIdAndDeletedAtIsNull(any());
    }

    @Test
    void getStores_returnsOnlyMemberStores_whenRegularUser() {
        when(storeMemberRepository.findByUserIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(ownerMember));

        List<StoreResponse> result = storeService.getStores(ownerPrincipal);

        assertThat(result).hasSize(1);
        verify(storeRepository, never()).findAll();
    }

    // ── getStore ──────────────────────────────────────────────────────────────

    @Test
    void getStore_returnsStore_whenFound() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));

        StoreResponse result = storeService.getStore(10L);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.name()).isEqualTo("Test Store");
    }

    @Test
    void getStore_throwsNotFound_whenStoreNotExist() {
        when(storeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getStore(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── setStoreStatus ────────────────────────────────────────────────────────

    @Test
    void setStoreStatus_updatesIsActive() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeRepository.save(store)).thenReturn(store);

        StoreResponse result = storeService.setStoreStatus(10L, false);

        assertThat(store.getIsActive()).isFalse();
        verify(storeRepository).save(store);
    }

    // ── updateStore ───────────────────────────────────────────────────────────

    @Test
    void updateStore_updatesFields() {
        StoreCreateRequest request = new StoreCreateRequest("New Name", "New Address", null, null);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeRepository.save(store)).thenReturn(store);

        StoreResponse result = storeService.updateStore(10L, request);

        assertThat(store.getName()).isEqualTo("New Name");
        assertThat(store.getAddress()).isEqualTo("New Address");
        verify(storeRepository).save(store);
    }

    // ── getMembers ────────────────────────────────────────────────────────────

    @Test
    void getMembers_returnsMembersWithRoles() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findByStoreIdAndIsActiveAndDeletedAtIsNull(10L, true))
                .thenReturn(List.of(ownerMember, managerMember));
        when(userRoleRepository.findByStoreIdAndIsActiveTrueAndDeletedAtIsNull(10L))
                .thenReturn(List.of(ownerUserRole, managerUserRole));

        List<StoreMemberResponse> result = storeService.getMembers(10L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).role()).isEqualTo(RoleName.ROLE_OWNER);
        assertThat(result.get(1).role()).isEqualTo(RoleName.ROLE_MANAGER);
    }

    // ── addMember ─────────────────────────────────────────────────────────────

    @Test
    void addMember_success() {
        AddMemberRequest request = new AddMemberRequest(2L, RoleName.ROLE_MANAGER, "Sales", true);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(userRoleRepository.findActiveStoreRole(2L, 10L)).thenReturn(Optional.empty());
        when(userRepository.findById(2L)).thenReturn(Optional.of(managerUser));
        when(roleRepository.findByName(RoleName.ROLE_MANAGER)).thenReturn(Optional.of(managerRole));
        when(storeMemberRepository.save(any())).thenReturn(managerMember);
        when(userRoleRepository.save(any())).thenReturn(managerUserRole);

        StoreMemberResponse result = storeService.addMember(10L, request);

        assertThat(result.userId()).isEqualTo(2L);
        verify(storeAccessEvaluator).evictStoreRoleCache(2L, 10L);
    }

    @Test
    void addMember_throwsWhenAlreadyMember() {
        AddMemberRequest request = new AddMemberRequest(2L, RoleName.ROLE_MANAGER, null, true);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(userRoleRepository.findActiveStoreRole(2L, 10L)).thenReturn(Optional.of(managerUserRole));

        assertThatThrownBy(() -> storeService.addMember(10L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User is already a member of this store");
    }

    // ── updateMember ──────────────────────────────────────────────────────────

    @Test
    void updateMember_success() {
        UpdateMemberRequest request = new UpdateMemberRequest(RoleName.ROLE_STAFF, "Cashier", true);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findById(2L)).thenReturn(Optional.of(managerMember));
        when(userRoleRepository.findActiveStoreRole(2L, 10L)).thenReturn(Optional.of(managerUserRole));
        when(roleRepository.findByName(RoleName.ROLE_STAFF))
                .thenReturn(Optional.of(Role.builder().name(RoleName.ROLE_STAFF).build()));
        when(userRoleRepository.save(any())).thenReturn(managerUserRole);
        when(storeMemberRepository.save(any())).thenReturn(managerMember);

        storeService.updateMember(10L, 2L, request, ownerPrincipal);

        verify(userRoleRepository).save(managerUserRole);
        verify(storeAccessEvaluator).evictStoreRoleCache(2L, 10L);
    }

    @Test
    void updateMember_throwsForbidden_whenModifyingAnotherOwner() {
        UserPrincipal anotherUser = new UserPrincipal(2L, "another", List.of(RoleName.ROLE_OWNER.name()));
        UpdateMemberRequest request = new UpdateMemberRequest(RoleName.ROLE_MANAGER, null, true);
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findById(1L)).thenReturn(Optional.of(ownerMember));
        when(userRoleRepository.findActiveStoreRole(1L, 10L)).thenReturn(Optional.of(ownerUserRole));

        assertThatThrownBy(() -> storeService.updateMember(10L, 1L, request, anotherUser))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── removeMember ──────────────────────────────────────────────────────────

    @Test
    void removeMember_success_whenNonOwner() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findById(2L)).thenReturn(Optional.of(managerMember));
        when(userRoleRepository.findActiveStoreRole(2L, 10L)).thenReturn(Optional.of(managerUserRole));

        storeService.removeMember(10L, 2L);

        assertThat(managerMember.getDeletedAt()).isNotNull();
        assertThat(managerUserRole.getDeletedAt()).isNotNull();
        verify(storeAccessEvaluator).evictStoreRoleCache(2L, 10L);
    }

    @Test
    void removeMember_throwsForbidden_whenRemovingOwner() {
        when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        when(storeMemberRepository.findById(1L)).thenReturn(Optional.of(ownerMember));
        when(userRoleRepository.findActiveStoreRole(1L, 10L)).thenReturn(Optional.of(ownerUserRole));

        assertThatThrownBy(() -> storeService.removeMember(10L, 1L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Cannot remove the OWNER from store");
    }
}
