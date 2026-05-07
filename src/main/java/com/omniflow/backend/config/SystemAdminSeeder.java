package com.omniflow.backend.config;

import com.omniflow.backend.entity.AdminProfile;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.entity.enums.SystemRole;
import com.omniflow.backend.repository.AdminProfileRepository;
import com.omniflow.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SystemAdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AdminProfileRepository adminProfileRepository;
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

    @Value("${admin.seed.department:}")
    private String department;

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

        SystemRole systemRole;
        try {
            systemRole = SystemRole.valueOf(role);
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
                        .createdAt(java.time.LocalDateTime.now())
                        .updatedAt(java.time.LocalDateTime.now())
                        .build()));

        if (adminProfileRepository.findByUserIdAndDeletedAtIsNull(user.getId()).isPresent()) {
            return;
        }

        AdminProfile profile = AdminProfile.builder()
                .user(user)
                .systemRole(systemRole)
                .department(isBlank(department) ? null : department)
                .isActive(active)
                .createdAt(java.time.LocalDateTime.now())
                .updatedAt(java.time.LocalDateTime.now())
                .build();

        adminProfileRepository.save(profile);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
