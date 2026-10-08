package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.Customer;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.repository.BusinessRepository;
import com.quiktech.pos.repository.CustomerRepository;
import com.quiktech.pos.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class CustomerSeeder implements ApplicationRunner {

    private static final String BUSINESS_NAME = "Business One";
    private static final String CREATED_BY_USERNAME = "user1";
    private static final ZoneId SEED_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // Khách được tạo trước khoảng lịch sử Order dự kiến (0–3 tháng trước ngày seed).
    // Khoảng ngày: [ngày seed - FROM_MONTHS_AGO, ngày seed - TO_MONTHS_AGO).
    private static final int FROM_MONTHS_AGO = 4;
    private static final int TO_MONTHS_AGO = 3;

    // Giữ nguyên mã để chạy lại không tạo trùng; thông tin liên hệ chỉ là dữ liệu mẫu.
    private static final List<Sample> CUSTOMERS = List.of(
            new Sample("CUS-SEED-001", "Nguyễn Minh Anh", "0903000001", "12 Nguyễn Trãi, Quận 1, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-002", "Trần Hoàng Nam", "0903000002", "45 Lê Văn Sỹ, Quận 3, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-003", "Lê Thu Hà", "0903000003", "28 Phan Đăng Lưu, Bình Thạnh, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-004", "Phạm Quốc Bảo", "0903000004", "76 Hoàng Hoa Thám, Tân Bình, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-005", "Hoàng Ngọc Mai", "0903000005", "19 Nguyễn Kiệm, Phú Nhuận, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-006", "Vũ Đức Long", "0903000006", "63 Lê Văn Việt, TP. Thủ Đức, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-007", "Đặng Thanh Trúc", "0903000007", "31 Nguyễn Thị Thập, Quận 7, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-008", "Bùi Gia Huy", "0903000008", "84 Quang Trung, Gò Vấp, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-009", "Đỗ Phương Linh", "0903000009", "52 Lũy Bán Bích, Tân Phú, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-010", "Hồ Tuấn Kiệt", "0903000010", "17 Hậu Giang, Quận 6, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-011", "Ngô Bảo Châu", "0903000011", "96 Võ Văn Tần, Quận 3, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-012", "Dương Thành Đạt", "0903000012", "24 Điện Biên Phủ, Bình Thạnh, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-013", "Lý Khánh Vy", "0903000013", "58 Nguyễn Văn Cừ, Quận 5, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-014", "Võ Anh Khoa", "0903000014", "39 Đỗ Xuân Hợp, TP. Thủ Đức, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-015", "Đinh Mỹ Duyên", "0903000015", "71 Phạm Văn Chiêu, Gò Vấp, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-016", "Nguyễn Hữu Phúc", "0903000016", "15 Bến Vân Đồn, Quận 4, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-017", "Trần Ngọc Hân", "0903000017", "43 Âu Cơ, Tân Bình, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-018", "Lê Trung Hiếu", "0903000018", "67 Phan Xích Long, Phú Nhuận, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-019", "Phạm Thảo Nhi", "0903000019", "22 Tô Hiến Thành, Quận 10, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-020", "Hoàng Minh Quân", "0903000020", "89 Kinh Dương Vương, Bình Tân, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-021", "Vũ Bích Ngọc", "0903000021", "34 Nguyễn Hữu Thọ, Quận 7, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-022", "Đặng Hải Đăng", "0903000022", "56 Thống Nhất, TP. Thủ Đức, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-023", "Bùi Thanh Tâm", "0903000023", "18 Trần Hưng Đạo, Quận 1, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-024", "Đỗ Nhật Minh", "0903000024", "73 Lạc Long Quân, Quận 11, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-025", "Hồ Diễm My", "0903000025", "41 Nguyễn Oanh, Gò Vấp, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-026", "Ngô Quốc Tuấn", "0903000026", "65 Trường Chinh, Tân Phú, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-027", "Dương Quỳnh Như", "0903000027", "27 Xô Viết Nghệ Tĩnh, Bình Thạnh, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-028", "Lý Thiên Phú", "0903000028", "92 Phạm Thế Hiển, Quận 8, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-029", "Võ Kim Ngân", "0903000029", "36 Nguyễn Văn Đậu, Phú Nhuận, TP. Hồ Chí Minh"),
            new Sample("CUS-SEED-030", "Đinh Xuân Hùng", "0903000030", "54 Lê Đức Thọ, Gò Vấp, TP. Hồ Chí Minh")
    );

    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    @Value("#{${seed.enabled:false} and ${customer.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Customer seeder is disabled");
            return;
        }
        if (FROM_MONTHS_AGO <= TO_MONTHS_AGO || TO_MONTHS_AGO < 0) {
            throw new IllegalStateException("Customer seed requires FROM_MONTHS_AGO > TO_MONTHS_AGO >= 0");
        }

        Business business = businessRepository.findByNameAndDeletedAtIsNull(BUSINESS_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Business not found: " + BUSINESS_NAME + " — run BusinessSeeder first"));
        User createdBy = userRepository.findByUsername(CREATED_BY_USERNAME)
                .orElseThrow(() -> new IllegalStateException(
                        "User not found: " + CREATED_BY_USERNAME + " — run UserSeeder first"));

        LocalDate seedDate = LocalDate.now(SEED_ZONE);
        long from = seedDate.minusMonths(FROM_MONTHS_AGO).atStartOfDay(SEED_ZONE).toEpochSecond();
        long to = seedDate.minusMonths(TO_MONTHS_AGO).atStartOfDay(SEED_ZONE).toEpochSecond();
        SeedSummary summary = new SeedSummary();

        for (int i = 0; i < CUSTOMERS.size(); i++) {
            Sample sample = CUSTOMERS.get(i);
            if (customerRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(business.getId(), sample.code()).isPresent()) {
                summary.record(false);
                continue;
            }

            Instant createdAt = Instant.ofEpochSecond(ThreadLocalRandom.current().nextLong(from, to));
            Customer customer = Customer.builder()
                    .business(business)
                    .code(sample.code())
                    .name(sample.name())
                    .phone(sample.phone())
                    .email(i % 3 == 2 ? null : sample.code().toLowerCase(Locale.ROOT) + "@example.com")
                    .address(sample.address())
                    .debtBalance(BigDecimal.ZERO)
                    .publicId(UUID.randomUUID())
                    .createdBy(createdBy)
                    .lastModifiedByUser(createdBy)
                    .createdAt(createdAt)
                    .updatedAt(createdAt)
                    .lastModifiedAt(createdAt)
                    .build();
            // Bản ghi chắc chắn mới: persist trực tiếp để giữ nguyên audit ngày lịch sử.
            entityManager.persist(customer);
            summary.record(true);
        }

        summary.logAfterCommit(log, "Customer");
    }

    private record Sample(String code, String name, String phone, String address) {}
}
