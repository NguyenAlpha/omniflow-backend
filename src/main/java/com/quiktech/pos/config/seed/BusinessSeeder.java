package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.BusinessMember;
import com.quiktech.pos.entity.Subscription;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.UserRole;
import com.quiktech.pos.entity.enums.PlanLimits;
import com.quiktech.pos.entity.enums.RoleName;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;
import com.quiktech.pos.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class BusinessSeeder implements ApplicationRunner {

    private final BusinessRepository businessRepository;
    private final BusinessMemberRepository businessMemberRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final SubscriptionRepository subscriptionRepository;

    // Chạy khi SEED_ENABLED=true VÀ business.seed.enabled=true
    @Value("#{${seed.enabled:false} and ${business.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Business seeder is disabled");
            return;
        }

        SeedSummary summary = new SeedSummary();

        seedBusiness(summary, "Business One", "user1", Instant.parse("2026-02-22T11:20:00Z"));
        seedBusiness(summary, "Business Two", "user2", Instant.parse("2026-03-15T09:36:00Z"));
        summary.logAfterCommit(log, "Business");
    }

    private void seedBusiness(SeedSummary summary, String name, String ownerUsername, Instant createdAt) {
        User owner = userRepository.findByUsername(ownerUsername)
                .orElseThrow(() -> new IllegalStateException("User not found: " + ownerUsername + " — run UserSeeder first"));

        Business business = summary.getOrCreate(businessRepository.findByNameAndDeletedAtIsNull(name),
                () -> businessRepository.save(Business.builder()
                        .name(name)
                        .createdAt(createdAt)
                        .updatedAt(createdAt)
                        .isActive(true)
                        .build()));

        businessMemberRepository.findByUserIdAndBusinessIdAndDeletedAtIsNull(owner.getId(), business.getId())
                .orElseGet(() -> businessMemberRepository.save(BusinessMember.builder()
                        .user(owner)
                        .business(business)
                        .joinedDate(LocalDate.now())
                        .isActive(true)
                        .publicId(UUID.randomUUID())
                        .build()));

        subscriptionRepository.findByBusinessId(business.getId())
                .orElseGet(() -> {
                    PlanLimits limits = PlanLimits.FREE;
                    return subscriptionRepository.save(Subscription.builder()
                            .business(business)
                            .plan(SubscriptionPlan.FREE)
                            .status(SubscriptionStatus.ACTIVE)
                            .maxStores(limits.maxStores)
                            .maxStaff(limits.maxStaff)
                            .maxProducts(limits.maxProducts)
                            .maxWarehouses(limits.maxWarehouses)
                            .startedAt(Instant.now())
                            .build());
                });

        boolean hasRole = userRoleRepository.findActiveBusinessRole(owner.getId(), business.getId()).isPresent();
        if (!hasRole) {
            var role = roleRepository.findByName(RoleName.ROLE_OWNER)
                    .orElseThrow(() -> new IllegalStateException("Role not found in DB: " + RoleName.ROLE_OWNER));
            userRoleRepository.save(UserRole.builder()
                    .user(owner)
                    .role(role)
                    .business(business)
                    .store(null)
                    .isActive(true)
                    .build());
        }
    }
}
