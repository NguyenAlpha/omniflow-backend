package com.quiktech.backend.config.seed;

import com.quiktech.backend.entity.Warehouse;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.WarehouseRepository;
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
public class WarehouseSeeder implements ApplicationRunner {

    private final WarehouseRepository warehouseRepository;
    private final StoreRepository storeRepository;

    // Chạy khi SEED_ENABLED=true VÀ warehouse.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${warehouse.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;

        seedWarehouse("Store One", "123 Đường A, Quận 1, TP.HCM");
        seedWarehouse("Store Two", "456 Đường B, Quận 2, TP.HCM");
    }

    private void seedWarehouse(String storeName, String address) {
        var store = storeRepository.findByNameAndDeletedAtIsNull(storeName)
                .orElseThrow(() -> new IllegalStateException("Store not found: " + storeName + " — run StoreSeeder first"));

        warehouseRepository.findByStoreIdAndNameAndDeletedAtIsNull(store.getId(), "Kho chính")
                .orElseGet(() -> warehouseRepository.save(Warehouse.builder()
                        .store(store)
                        .name("Kho chính")
                        .address(address)
                        .publicId(UUID.randomUUID())
                        .build()));
    }
}
