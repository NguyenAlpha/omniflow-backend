package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.Store;
import com.quiktech.pos.repository.BusinessRepository;
import com.quiktech.pos.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class StoreSeeder implements ApplicationRunner {

    private final StoreRepository storeRepository;
    private final BusinessRepository businessRepository;

    // Chạy khi SEED_ENABLED=true VÀ store.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${store.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Store seeder is disabled");
            return;
        }

        SeedSummary summary = new SeedSummary();

        seedStore(summary, "Business One", "Store One", "123 Đường A, Quận 1, TP.HCM", "0901000001", "store1@example.com", Instant.parse("2026-02-23T09:11:00Z"));
        seedStore(summary, "Business Two", "Store Two", "456 Đường B, Quận 2, TP.HCM", "0901000002", "store2@example.com", Instant.parse("2026-03-17T11:23:00Z"));
        summary.logAfterCommit(log, "Store");
    }

    private void seedStore(SeedSummary summary, String businessName, String storeName, String address, String phone, String email, Instant created) {
        Business business = businessRepository.findByNameAndDeletedAtIsNull(businessName)
                .orElseThrow(() -> new IllegalStateException("Business not found: " + businessName + " — run BusinessSeeder first"));

        summary.getOrCreate(storeRepository.findByNameAndDeletedAtIsNull(storeName),
                () -> storeRepository.save(Store.builder()
                        .business(business)
                        .name(storeName)
                        .address(address)
                        .phone(phone)
                        .email(email)
                        .createdAt(created)
                        .updatedAt(created)
                        .build()));
    }
}
