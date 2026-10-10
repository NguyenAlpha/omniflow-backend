package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.user.ChangePasswordRequest;
import com.quiktech.pos.dto.request.user.UpdateProfileRequest;
import com.quiktech.pos.dto.response.auth.BusinessMembershipResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.exception.BusinessRuleException;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuthResponseAssembler authResponseAssembler;

    @InjectMocks private UserService userService;

    private final UserPrincipal currentUser = new UserPrincipal(7L, "an", List.of());

    // ── updateProfile ─────────────────────────────────────────────────────────

    @Test
    void updateProfile_usernameOfAnotherUser_throwsUsernameTaken() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L)));
        when(userRepository.findByUsername("binh")).thenReturn(Optional.of(user(8L)));

        assertThatThrownBy(() -> userService.updateProfile(currentUser, profile("binh", "an@test.com")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USERNAME_TAKEN);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateProfile_emailOfAnotherUser_throwsEmailTaken() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L)));
        when(userRepository.findByUsername("an")).thenReturn(Optional.of(user(7L)));
        when(userRepository.findByEmail("binh@test.com")).thenReturn(Optional.of(user(8L)));

        assertThatThrownBy(() -> userService.updateProfile(currentUser, profile("an", "binh@test.com")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_TAKEN);
        verify(userRepository, never()).saveAndFlush(any());
    }

    // ── changePassword ────────────────────────────────────────────────────────

    @Test
    void changePassword_wrongCurrentPassword_throwsInvalidCurrentPassword_andKeepsSessions() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L)));
        when(passwordEncoder.matches("wrong", "bcrypt-hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(currentUser, new ChangePasswordRequest("wrong", "newpass1")))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(refreshTokenService);
    }

    // ── getMemberships ────────────────────────────────────────────────────────

    @Test
    void getMemberships_returnsSameMembershipsAsLoginResponse() {
        User user = user(7L);
        List<BusinessMembershipResponse> memberships = List.of(new BusinessMembershipResponse(5L, "Coffee", List.of()));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(authResponseAssembler.resolveMemberships(user)).thenReturn(memberships);

        assertThat(userService.getMemberships(currentUser)).isSameAs(memberships);
    }

    private static UpdateProfileRequest profile(String username, String email) {
        return new UpdateProfileRequest(username, email, "Nguyễn An", null);
    }

    private static User user(Long id) {
        return User.builder().id(id).username("an").email("an@test.com")
                .passwordHash("bcrypt-hash").fullName("Nguyễn An").isActive(true).build();
    }
}
