package com.quiktech.pos.service;

import com.quiktech.pos.entity.Subscription;
import com.quiktech.pos.entity.enums.InvoiceStatus;
import com.quiktech.pos.entity.SubscriptionPlanConfig;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;
import com.quiktech.pos.repository.SubscriptionInvoiceRepository;
import com.quiktech.pos.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanCatalogService planCatalogService;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final EmailService emailService;

    // Số ngày invoice PENDING được phép tồn tại trước khi bị auto-huỷ
    @Value("${subscription.invoice.pending-ttl-days:7}")
    private int pendingInvoiceTtlDays;

    /**
     * Chạy hàng ngày lúc 01:00 AM (cấu hình qua subscription.expiry.cron).
     * 1. Áp dụng pending downgrade cho các sub đã hết hạn (FREE → ACTIVE, paid → EXPIRED).
     * 2. Bulk-expire các sub ACTIVE đã qua expiresAt còn lại.
     * 3. Gửi email cảnh báo sắp hết hạn.
     * 4. Auto-huỷ invoice PENDING quá hạn thanh toán.
     */
    @Scheduled(cron = "${subscription.expiry.cron:0 0 1 * * *}")
    @Transactional
    public void expireOverdueSubscriptions() {
        Instant now = Instant.now();

        // 1. Xử lý các subscription có pending downgrade
        List<Subscription> pendingDowngrades = subscriptionRepository
                .findOverdueWithPendingPlan(SubscriptionStatus.ACTIVE, now);

        for (Subscription sub : pendingDowngrades) {
            SubscriptionPlan targetPlan = sub.getPendingPlan();
            SubscriptionPlanConfig limits = planCatalogService.limitsFor(targetPlan);

            sub.setPlan(targetPlan);
            limits.applyLimitsTo(sub);
            sub.setPendingPlan(null);
            sub.setPendingBillingCycle(null);

            if (targetPlan == SubscriptionPlan.FREE) {
                // FREE không có expiry — giữ ACTIVE mãi
                sub.setStatus(SubscriptionStatus.ACTIVE);
                sub.setExpiresAt(null);
                sub.setBillingCycle(null);
            } else {
                // Paid plan thấp hơn: EXPIRED, user cần re-subscribe
                sub.setStatus(SubscriptionStatus.EXPIRED);
            }
        }

        if (!pendingDowngrades.isEmpty()) {
            subscriptionRepository.saveAll(pendingDowngrades);
            log.info("Applied pending downgrade for {} subscription(s)", pendingDowngrades.size());
        }

        // 2. Bulk-expire phần còn lại (không có pending plan)
        // Hạ limit về FREE ngay trong UPDATE — sub EXPIRED không được giữ quyền lợi
        // gói trả phí (soft cap: data hiện có không bị xóa, chỉ chặn tạo mới vượt FREE)
        SubscriptionPlanConfig freeLimits = planCatalogService.limitsFor(SubscriptionPlan.FREE);
        int expired = subscriptionRepository.expireOverdue(
                SubscriptionStatus.EXPIRED,
                SubscriptionStatus.ACTIVE,
                now,
                freeLimits.getMaxStores(),
                freeLimits.getMaxStaff(),
                freeLimits.getMaxProducts(),
                freeLimits.getMaxWarehouses());

        if (expired > 0) {
            log.info("Expired {} subscription(s) past their expiresAt", expired);
        }

        // 3. Gửi email cảnh báo các subscription ACTIVE sắp hết hạn trong 7 ngày tới
        // (findExpiringSoon chỉ trả sub chưa gửi — expiryWarningSentAt IS NULL)
        Instant warningDeadline = now.plus(7, ChronoUnit.DAYS);
        List<Subscription> expiringSoon = subscriptionRepository.findExpiringSoon(
                SubscriptionStatus.ACTIVE, now, warningDeadline);

        for (Subscription sub : expiringSoon) {
            emailService.sendSubscriptionExpiryWarning(
                    sub.getBusiness().getEmail(),
                    sub.getBusiness().getName(),
                    sub.getExpiresAt());
            // Đánh dấu đã gửi cho chu kỳ hiện tại — mỗi chu kỳ chỉ cảnh báo 1 lần
            sub.setExpiryWarningSentAt(now);
        }

        if (!expiringSoon.isEmpty()) {
            subscriptionRepository.saveAll(expiringSoon);
            log.info("Sent expiry warning emails for {} subscription(s)", expiringSoon.size());
        }

        // 4. Auto-huỷ invoice PENDING quá hạn — giá plan có thể đã đổi, không cho
        // confirm invoice tạo từ quá lâu (owner phải tạo yêu cầu nâng cấp mới)
        Instant invoiceCutoff = now.minus(pendingInvoiceTtlDays, ChronoUnit.DAYS);
        int cancelled = invoiceRepository.failStalePending(
                InvoiceStatus.FAILED,
                InvoiceStatus.PENDING,
                invoiceCutoff,
                "Auto-cancelled: pending invoice not paid within " + pendingInvoiceTtlDays + " days");

        if (cancelled > 0) {
            log.info("Auto-cancelled {} stale PENDING invoice(s)", cancelled);
        }
    }
}
