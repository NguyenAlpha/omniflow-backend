package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.User;
import com.quiktech.pos.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class UserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // Chạy khi SEED_ENABLED=true VÀ user.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${user.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;

        int createdCount = 0;
        if (seedUser("user1", "u1@u.com", "User One", Instant.parse("2025-01-15T08:30:00Z"))) {
            createdCount++;
        }
        if (seedUser("user2", "u2@u.com", "User Two", Instant.parse("2025-03-08T10:15:00Z"))) {
            createdCount++;
        }
        if (seedUser("user3", "u3@u.com", "User Three", Instant.parse("2025-06-21T14:45:00Z"))) {
            createdCount++;
        }
        if (seedUser("user4", "u4@u.com", "User Four", Instant.parse("2025-02-21T14:45:00Z"))) {
            createdCount++;
        }

        int totalCount = 4;
        log.info("User seed completed: created={}, skipped={}", createdCount, totalCount - createdCount);
    }

    private boolean seedUser(String username, String email, String fullName, Instant createdAt) {
        if (userRepository.findByUsername(username).isPresent()) {
            return false;
        }

        userRepository.save(User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode("password"))
                .fullName(fullName)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
        return true;
    }
}
