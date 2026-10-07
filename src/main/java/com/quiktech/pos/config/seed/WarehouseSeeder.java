package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.Warehouse;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.WarehouseRepository;
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
public class WarehouseSeeder implements ApplicationRunner {

    private final WarehouseRepository warehouseRepository;
    private final StoreRepository storeRepository;

    // Chạy khi SEED_ENABLED=true VÀ warehouse.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${warehouse.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Warehouse seeder is disabled");
            return;
        }

        SeedSummary summary = new SeedSummary();

        seedWarehouse(summary, "Store One", "123 Đường A, Quận 1, TP.HCM");
        seedWarehouse(summary, "Store Two", "456 Đường B, Quận 2, TP.HCM");
        summary.logAfterCommit(log, "Warehouse");
    }

    private void seedWarehouse(SeedSummary summary, String storeName, String address) {
        var store = storeRepository.findByNameAndDeletedAtIsNull(storeName)
                .orElseThrow(() -> new IllegalStateException("Store not found: " + storeName + " — run StoreSeeder first"));

        summary.getOrCreate(warehouseRepository.findByStoreIdAndNameAndDeletedAtIsNull(store.getId(), "Kho chính"),
                () -> warehouseRepository.save(Warehouse.builder()
                        .store(store)
                        .name("Kho chính")
                        .address(address)
                        .publicId(UUID.randomUUID())
                        .build()));
    }
}
