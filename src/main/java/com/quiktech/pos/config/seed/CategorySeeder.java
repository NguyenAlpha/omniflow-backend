package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.Category;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.repository.BusinessRepository;
import com.quiktech.pos.repository.CategoryRepository;
import com.quiktech.pos.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        if (!enabled) {
            log.debug("Category seeder is disabled");
            return;
        }

        SeedSummary summary = new SeedSummary();

        seedCategory(summary, "Business One", "Thực phẩm", null, "user1");
        seedCategory(summary, "Business One", "Đồ uống", null, "user1");
        seedCategory(summary, "Business Two", "Văn phòng phẩm", null, "user2");
        seedCategory(summary, "Business Two", "Đồ điện tử", null, "user2");
        summary.logAfterCommit(log, "Category");
    }

    private void seedCategory(SeedSummary summary, String businessName, String categoryName, String description, String createdByUsername) {
        var business = businessRepository.findByNameAndDeletedAtIsNull(businessName)
                .orElseThrow(() -> new IllegalStateException("Business not found: " + businessName + " — run BusinessSeeder first"));

        User createdBy = userRepository.findByUsername(createdByUsername)
                .orElseThrow(() -> new IllegalStateException("User not found: " + createdByUsername + " — run UserSeeder first"));

        summary.getOrCreate(categoryRepository.findByBusinessIdAndNameAndDeletedAtIsNull(business.getId(), categoryName),
                () -> categoryRepository.save(Category.builder()
                        .business(business)
                        .name(categoryName)
                        .description(description)
                        .publicId(UUID.randomUUID())
                        .createdBy(createdBy)
                        .lastModifiedByUser(createdBy)
                        .build()));
    }
}
