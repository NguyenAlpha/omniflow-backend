package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.subscription.PlanUpdateRequest;
import com.quiktech.pos.dto.response.admin.AdminPlanResponse;
import com.quiktech.pos.dto.response.subscription.PlanResponse;
import com.quiktech.pos.entity.SubscriptionPlanConfig;
import com.quiktech.pos.entity.enums.BillingCycle;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;
import com.quiktech.pos.repository.SubscriptionPlanConfigRepository;
import com.quiktech.pos.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Nguồn duy nhất cho giá và giới hạn của các gói (bảng subscription_plans).
 *
 * <p>subscriptions.max_* là bản sao limit: sub ACTIVE mang limit của gói đang dùng,
 * sub không ACTIVE (EXPIRED) mang limit FREE. Admin sửa gói thì cập nhật luôn các
 * bản sao đó trong cùng transaction — giới hạn mới có hiệu lực ngay.
 */
@Service
@RequiredArgsConstructor
public class PlanCatalogService {

    private final SubscriptionPlanConfigRepository plans;
    private final SubscriptionRepository subscriptions;
    private final AdminAuditService audit;

    @Transactional(readOnly = true)
    public List<PlanResponse> list() {
        return sorted().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<AdminPlanResponse> listForAdmin() {
        return sorted().stream().map(this::toAdminResponse).toList();
    }

    // Giá được chép vào invoice.amount lúc tạo → đổi giá sau đó không ảnh hưởng invoice cũ
    @Transactional(readOnly = true)
    public BigDecimal priceFor(SubscriptionPlan plan, BillingCycle cycle) {
        return plans.findById(plan).orElseThrow().priceFor(cycle);
    }

    /**
     * Limit để chép sang subscription. Khóa FOR SHARE tới khi caller commit để không
     * chạy chéo với admin đang sửa gói (xem SubscriptionPlanConfigRepository#findForShare).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SubscriptionPlanConfig limitsFor(SubscriptionPlan plan) {
        return plans.findForShare(plan).orElseThrow();
    }

    @Transactional
    public AdminPlanResponse update(SubscriptionPlan code, PlanUpdateRequest request) {
        SubscriptionPlanConfig plan = plans.findForUpdate(code).orElseThrow();
        if (!request.version().equals(plan.getVersion())) {
            throw new OptimisticLockingFailureException("Plan changed; reload before continuing");
        }
        if (code == SubscriptionPlan.FREE
                && (request.monthlyPrice().signum() != 0 || request.yearlyPrice().signum() != 0)) {
            throw new IllegalArgumentException("FREE plan price must be 0");
        }
        AdminPlanResponse before = toAdminResponse(plan);

        plan.setMonthlyPrice(request.monthlyPrice());
        plan.setYearlyPrice(request.yearlyPrice());
        plan.setMaxStores(request.maxStores());
        plan.setMaxStaff(request.maxStaff());
        plan.setMaxProducts(request.maxProducts());
        plan.setMaxWarehouses(request.maxWarehouses());
        plans.saveAndFlush(plan);

        subscriptions.applyPlanLimits(code, SubscriptionStatus.ACTIVE,
                plan.getMaxStores(), plan.getMaxStaff(), plan.getMaxProducts(), plan.getMaxWarehouses());
        if (code == SubscriptionPlan.FREE) {
            subscriptions.applyFreeLimitsToInactive(SubscriptionStatus.ACTIVE,
                    plan.getMaxStores(), plan.getMaxStaff(), plan.getMaxProducts(), plan.getMaxWarehouses());
        }

        AdminPlanResponse result = toAdminResponse(plan);
        audit.record("ADMIN_PLAN_CONFIG_UPDATED", "SUBSCRIPTION_PLAN", null, null, request.reason(), before, result);
        return result;
    }

    private List<SubscriptionPlanConfig> sorted() {
        return plans.findAll().stream().sorted(Comparator.comparing(SubscriptionPlanConfig::getCode)).toList();
    }

    private long affectedBusinesses(SubscriptionPlan code) {
        long active = subscriptions.countByPlanAndStatus(code, SubscriptionStatus.ACTIVE);
        return code == SubscriptionPlan.FREE ? active + subscriptions.countByStatusNot(SubscriptionStatus.ACTIVE) : active;
    }

    private PlanResponse toResponse(SubscriptionPlanConfig p) {
        return new PlanResponse(p.getCode(), p.getMonthlyPrice(), p.getYearlyPrice(),
                p.getMaxStores(), p.getMaxStaff(), p.getMaxProducts(), p.getMaxWarehouses());
    }

    private AdminPlanResponse toAdminResponse(SubscriptionPlanConfig p) {
        return new AdminPlanResponse(p.getCode(), p.getMonthlyPrice(), p.getYearlyPrice(),
                p.getMaxStores(), p.getMaxStaff(), p.getMaxProducts(), p.getMaxWarehouses(),
                p.getVersion(), p.getUpdatedAt(), affectedBusinesses(p.getCode()));
    }
}
