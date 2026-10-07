package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.Supplier;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.repository.BusinessRepository;
import com.quiktech.pos.repository.SupplierRepository;
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
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class SupplierSeeder implements ApplicationRunner {

    private final SupplierRepository supplierRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    // Chạy khi SEED_ENABLED=true VÀ supplier.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${supplier.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Supplier seeder is disabled");
            return;
        }

        SeedSummary summary = new SeedSummary();

        seedSupplier(summary, "Business One", "user1", "SUP-001", "Công ty Thực phẩm An Phát",
                "0901000001", "anphat@example.com", "Quận 1, TP. Hồ Chí Minh");
        seedSupplier(summary, "Business One", "user1", "SUP-002", "Nhà phân phối Đồ uống Việt",
                "0901000002", "douongviet@example.com", "Quận Bình Thạnh, TP. Hồ Chí Minh");
        seedSupplier(summary, "Business One", "user1", "SUP-003", "Nông sản Xanh",
                "0901000003", "nongsanxanh@example.com", "TP. Thủ Đức, TP. Hồ Chí Minh");

        seedSupplier(summary, "Business Two", "user2", "SUP-001", "Văn phòng phẩm Minh Long",
                "0902000001", "minhlong@example.com", "Quận Hải Châu, Đà Nẵng");
        seedSupplier(summary, "Business Two", "user2", "SUP-002", "Thiết bị Công nghệ Sao Việt",
                "0902000002", "saoviet@example.com", "Quận Thanh Khê, Đà Nẵng");
        seedSupplier(summary, "Business Two", "user2", "SUP-003", "Điện máy Thành Công",
                "0902000003", "thanhcong@example.com", "Quận Sơn Trà, Đà Nẵng");
        summary.logAfterCommit(log, "Supplier");
    }

    private void seedSupplier(
            SeedSummary summary,
            String businessName,
            String createdByUsername,
            String code,
            String name,
            String phone,
            String email,
            String address
    ) {
        Business business = businessRepository.findByNameAndDeletedAtIsNull(businessName)
                .orElseThrow(() -> new IllegalStateException(
                        "Business not found: " + businessName + " — run BusinessSeeder first"));

        User createdBy = userRepository.findByUsername(createdByUsername)
                .orElseThrow(() -> new IllegalStateException(
                        "User not found: " + createdByUsername + " — run UserSeeder first"));

        summary.getOrCreate(supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(business.getId(), code),
                () -> supplierRepository.save(Supplier.builder()
                        .business(business)
                        .code(code)
                        .name(name)
                        .phone(phone)
                        .email(email)
                        .address(address)
                        .publicId(UUID.randomUUID())
                        .createdBy(createdBy)
                        .lastModifiedByUser(createdBy)
                        .build()));
    }
}
