package com.quiktech.backend.config.seed;

import com.quiktech.backend.entity.User;
import com.quiktech.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
@RequiredArgsConstructor
public class UserSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${user.seed.enabled:false}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;

        seedUser("user1", "u1@u.com", "User One");
        seedUser("user2", "u2@u.com", "User Two");
        seedUser("user3", "u3@u.com", "User Three");
    }

    private void seedUser(String username, String email, String fullName) {
        userRepository.findByUsername(username)
                .orElseGet(() -> userRepository.save(User.builder()
                        .username(username)
                        .email(email)
                        .passwordHash(passwordEncoder.encode("password"))
                        .fullName(fullName)
                        .build()));
    }
}
