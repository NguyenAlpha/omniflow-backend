package com.quiktech.backend.config.seed;

import com.quiktech.backend.entity.Category;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.repository.BusinessRepository;
import com.quiktech.backend.repository.CategoryRepository;
import com.quiktech.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Order(4)
@RequiredArgsConstructor
public class CategorySeeder implements ApplicationRunner {

    private final CategoryRepository categoryRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    // Chạy khi SEED_ENABLED=true VÀ category.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${category.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;

        seedCategory("Business One", "Thực phẩm", null, "user1");
        seedCategory("Business One", "Đồ uống", null, "user1");
        seedCategory("Business Two", "Văn phòng phẩm", null, "user2");
        seedCategory("Business Two", "Đồ điện tử", null, "user2");
    }

    private void seedCategory(String businessName, String categoryName, String description, String createdByUsername) {
        var business = businessRepository.findByNameAndDeletedAtIsNull(businessName)
                .orElseThrow(() -> new IllegalStateException("Business not found: " + businessName + " — run BusinessSeeder first"));

        User createdBy = userRepository.findByUsername(createdByUsername)
                .orElseThrow(() -> new IllegalStateException("User not found: " + createdByUsername + " — run UserSeeder first"));

        categoryRepository.findByBusinessIdAndNameAndDeletedAtIsNull(business.getId(), categoryName)
                .orElseGet(() -> categoryRepository.save(Category.builder()
                        .business(business)
                        .name(categoryName)
                        .description(description)
                        .publicId(UUID.randomUUID())
                        .createdBy(createdBy)
                        .lastModifiedByUser(createdBy)
                        .build()));
    }
}
