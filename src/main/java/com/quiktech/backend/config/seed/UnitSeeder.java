package com.quiktech.backend.config.seed;

import com.quiktech.backend.entity.Unit;
import com.quiktech.backend.repository.UnitRepository;
import lombok.RequiredArgsConstructor;
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
public class UnitSeeder implements ApplicationRunner {

    private final UnitRepository unitRepository;

    @Value("${unit.seed.enabled:false}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (!unitRepository.findByBusinessIdIsNullAndDeletedAtIsNull().isEmpty()) return;

        unitRepository.saveAll(List.of(
                Unit.builder().name("Cái").abbreviation("cái").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Hộp").abbreviation("hộp").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Bộ").abbreviation("bộ").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Bao").abbreviation("bao").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Chai").abbreviation("chai").publicId(UUID.randomUUID()).build(),
                Unit.builder().name("Gói").abbreviation("gói").publicId(UUID.randomUUID()).build()
        ));
    }
}
