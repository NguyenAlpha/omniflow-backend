package com.quiktech.backend.config.seed;

import com.quiktech.backend.entity.Business;
import com.quiktech.backend.entity.BusinessMember;
import com.quiktech.backend.entity.Subscription;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.entity.UserRole;
import com.quiktech.backend.entity.enums.PlanLimits;
import com.quiktech.backend.entity.enums.RoleName;
import com.quiktech.backend.entity.enums.SubscriptionPlan;
import com.quiktech.backend.entity.enums.SubscriptionStatus;
import com.quiktech.backend.repository.*;
import lombok.RequiredArgsConstructor;
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
        if (!enabled) return;

        seedBusiness("Business One", "user1");
        seedBusiness("Business Two", "user2");
    }

    private void seedBusiness(String name, String ownerUsername) {
        User owner = userRepository.findByUsername(ownerUsername)
                .orElseThrow(() -> new IllegalStateException("User not found: " + ownerUsername + " — run UserSeeder first"));

        Business business = businessRepository.findByNameAndDeletedAtIsNull(name)
                .orElseGet(() -> businessRepository.save(Business.builder()
                        .name(name)
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
