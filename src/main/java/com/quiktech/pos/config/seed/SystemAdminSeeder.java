package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.Role;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.entity.enums.RoleName;
import com.quiktech.pos.repository.RoleRepository;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * SystemAdminSeeder được Spring chạy mỗi lần ứng dụng khởi động xong (sau khi ApplicationContext được tạo)
 * vì class này implement ApplicationRunner
 */
@Component
@RequiredArgsConstructor
@Slf4j
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
        if (!enabled) {
            log.debug("System admin seeder is disabled");
            return;
        }

        log.warn("System admin seeder is enabled");

        if (isBlank(username) || isBlank(email) || isBlank(password) || isBlank(fullName)) {
            throw new IllegalStateException("Admin seed requires username, email, password, and full-name");
        }

        RoleName roleName;
        try {
            roleName = RoleName.valueOf(role);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid admin.seed.role: " + role, ex);
        }

        log.debug("Starting system admin seed: username={}, role={}, active={}", username, roleName, active);

        SeedSummary summary = new SeedSummary();
        var existingUser = userRepository.findByUsernameOrEmail(username, email);
        User user;
        if (existingUser.isPresent()) {
            user = existingUser.get();
            log.debug("System admin user already exists: userId={}, username={}",
                    user.getId(), user.getUsername());
        } else {
            user = userRepository.save(User.builder()
                    .username(username)
                    .email(email)
                    .passwordHash(passwordEncoder.encode(password))
                    .fullName(fullName)
                    .phone(isBlank(phone) ? null : phone)
                    .isActive(active)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build());
            log.debug("Created system admin user (pending commit): userId={}, username={}",
                    user.getId(), user.getUsername());
        }

        summary.record(existingUser.isEmpty());

        if (userRoleRepository.existsByUserIdAndBusinessIsNullAndStoreIsNullAndDeletedAtIsNull(user.getId())) {
            log.debug("Skipping system role assignment because user already has one: userId={}, username={}",
                    user.getId(), user.getUsername());
            summary.record(false);
            summary.logAfterCommit(log, "System admin", "scope=users+roles");
            return;
        }

        Role roleEntity = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role not found in DB: " + roleName));

        UserRole userRole = userRoleRepository.save(UserRole.builder()
                .user(user)
                .role(roleEntity)
                .business(null)
                .store(null)
                .isActive(active)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        log.debug("Assigned system role (pending commit): userId={}, username={}, userRoleId={}, role={}, active={}",
                user.getId(), user.getUsername(), userRole.getId(), roleName, active);
        summary.record(true);
        summary.logAfterCommit(log, "System admin", "scope=users+roles");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
