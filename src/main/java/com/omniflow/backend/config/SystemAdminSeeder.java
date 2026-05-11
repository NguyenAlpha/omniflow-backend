package com.omniflow.backend.config;

import com.omniflow.backend.entity.Role;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.UserRole;
import com.omniflow.backend.entity.enums.RoleName;
import com.omniflow.backend.repository.RoleRepository;
import com.omniflow.backend.repository.UserRepository;
import com.omniflow.backend.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class SystemAdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.seed.enabled:false}")
    private boolean enabled;

    @Value("${admin.seed.username:}")
    private String username;

    @Value("${admin.seed.email:}")
    private String email;

    @Value("${admin.seed.password:}")
    private String password;

    @Value("${admin.seed.full-name:}")
    private String fullName;

    @Value("${admin.seed.role:SUPER_ADMIN}")
    private String role;

    @Value("${admin.seed.phone:}")
    private String phone;

    @Value("${admin.seed.active:true}")
    private boolean active;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;

        if (isBlank(username) || isBlank(email) || isBlank(password) || isBlank(fullName)) {
            throw new IllegalStateException("Admin seed requires username, email, password, and full-name");
        }

        RoleName roleName;
        try {
            roleName = RoleName.valueOf(role);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid admin.seed.role: " + role, ex);
        }

        User user = userRepository.findByUsernameOrEmail(username, email)
                .orElseGet(() -> userRepository.save(User.builder()
                        .username(username)
                        .email(email)
                        .passwordHash(passwordEncoder.encode(password))
                        .fullName(fullName)
                        .phone(isBlank(phone) ? null : phone)
                        .isActive(active)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build()));

        if (userRoleRepository.existsByUserIdAndStoreIsNullAndDeletedAtIsNull(user.getId())) {
            return;
        }

        Role roleEntity = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role not found in DB: " + roleName));

        userRoleRepository.save(UserRole.builder()
                .user(user)
                .role(roleEntity)
                .store(null)
                .isActive(active)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
