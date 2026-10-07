package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.Unit;
import com.quiktech.pos.repository.UnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class UnitSeeder implements ApplicationRunner {

    private final UnitRepository unitRepository;

    // Chạy khi SEED_ENABLED=true VÀ unit.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${unit.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Unit seeder is disabled");
            return;
        }

        SeedSummary summary = new SeedSummary();
        List<Unit> samples = List.of(
                Unit.builder().name("Cái").abbreviation("cái").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Hộp").abbreviation("hộp").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Bộ").abbreviation("bộ").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Bao").abbreviation("bao").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Chai").abbreviation("chai").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Gói").abbreviation("gói").publicId(UUID.randomUUID()).build()
        );
        // Giữ nguyên chính sách hiện tại: bỏ qua cả bộ mẫu nếu đã có unit hệ thống.
        boolean skipped = !unitRepository.findByBusinessIdIsNullAndDeletedAtIsNull().isEmpty();
        if (!skipped) {
            unitRepository.saveAll(samples);
        }
        samples.forEach(unit -> summary.record(!skipped));
        summary.logAfterCommit(log, "Unit");
    }
}
