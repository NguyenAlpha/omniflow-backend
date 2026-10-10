package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.auth.AuthResponse;
import com.quiktech.pos.dto.response.auth.StoreInfo;
import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.Role;
import com.quiktech.pos.entity.Store;
import com.quiktech.pos.entity.StoreMember;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.entity.enums.RoleName;
import com.quiktech.pos.repository.StoreMemberRepository;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import com.quiktech.pos.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthResponseAssemblerTest {

    @Mock private StoreMemberRepository storeMemberRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private JwtService jwtService;

    @InjectMocks private AuthResponseAssembler assembler;

    private User user;
    private Business business;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(assembler, "jwtExpiration", 3_600_000L);
        user = User.builder().id(7L).username("an").email("an@test.com").fullName("An").isActive(true).build();
        business = Business.builder().id(1L).name("Coffee Chain").build();
        when(jwtService.generateToken(eq(user), anyMap())).thenReturn("jwt-token");
    }

    @Test
    void owner_getsEveryStoreOfBusiness_withBusinessRole() {
        UserRole ownerRole = UserRole.builder().user(user).business(business).role(role(RoleName.ROLE_OWNER)).build();
        when(userRoleRepository.findActiveBusinessRolesForUser(7L)).thenReturn(List.of(ownerRole));
        when(storeRepository.findByBusinessIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(store(10L, "Q1"), store(11L, "Q3")));

        AuthResponse response = assembler.assemble(user, "refresh-token");

        assertThat(response.memberships()).singleElement().satisfies(m -> {
            assertThat(m.businessId()).isEqualTo(1L);
            assertThat(m.businessName()).isEqualTo("Coffee Chain");
            assertThat(m.stores()).containsExactly(
                    new StoreInfo(10L, "Q1", RoleName.ROLE_OWNER, null),
                    new StoreInfo(11L, "Q3", RoleName.ROLE_OWNER, null));
        });
    }

    @Test
    void staff_getsOnlyAssignedStores_withPositionTitle_groupedByBusiness() {
        Store q1 = store(10L, "Q1");
        Store q3 = store(11L, "Q3");
        when(userRoleRepository.findActiveStoreRolesWithBusinessDetails(7L)).thenReturn(List.of(
                UserRole.builder().user(user).store(q1).role(role(RoleName.ROLE_MANAGER)).build(),
                UserRole.builder().user(user).store(q3).role(role(RoleName.ROLE_STAFF)).build()));
        when(storeMemberRepository.findByUserIdAndDeletedAtIsNullWithStore(7L)).thenReturn(List.of(
                StoreMember.builder().user(user).store(q1).positionTitle("Trưởng ca").build()));

        AuthResponse response = assembler.assemble(user, "refresh-token");

        assertThat(response.memberships()).singleElement().satisfies(m ->
                assertThat(m.stores()).containsExactly(
                        new StoreInfo(10L, "Q1", RoleName.ROLE_MANAGER, "Trưởng ca"),
                        new StoreInfo(11L, "Q3", RoleName.ROLE_STAFF, null)));
    }

    @Test
    void activeGlobalRolesGoIntoJwt_andTokenFieldsArePassedThrough() {
        when(userRoleRepository.findByUserIdAndBusinessIsNullAndStoreIsNullAndDeletedAtIsNull(7L)).thenReturn(List.of(
                UserRole.builder().user(user).role(role(RoleName.ROLE_SUPER_ADMIN)).isActive(true).build(),
                UserRole.builder().user(user).role(role(RoleName.ROLE_SUPPORT)).isActive(false).build()));

        AuthResponse response = assembler.assemble(user, "refresh-token");

        verify(jwtService).generateToken(user, Map.of("userId", 7L, "roles", List.of("ROLE_SUPER_ADMIN")));
        assertThat(response.memberships()).isEmpty();
        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600L);
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.user().username()).isEqualTo("an");
    }

    private Store store(Long id, String name) {
        return Store.builder().id(id).name(name).business(business).build();
    }

    private static Role role(RoleName name) {
        return Role.builder().name(name).build();
    }
}
