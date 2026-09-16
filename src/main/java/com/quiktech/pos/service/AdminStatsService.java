package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.admin.AdminStatsResponse;
import com.quiktech.pos.entity.enums.InvoiceStatus;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;
import com.quiktech.pos.repository.BusinessRepository;
import com.quiktech.pos.repository.SubscriptionInvoiceRepository;
import com.quiktech.pos.repository.SubscriptionRepository;
import com.quiktech.pos.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final BusinessRepository businessRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public AdminStatsResponse getStats() {
        long totalBusinesses = businessRepository.count();
        long totalUsers = userRepository.countByDeletedAtIsNull();
        long activeUsers = userRepository.countByIsActiveAndDeletedAtIsNull(true);
        long pendingInvoices = invoiceRepository.countByStatus(InvoiceStatus.PENDING);

        long freePlan = subscriptionRepository.countByPlan(SubscriptionPlan.FREE);
        long basicPlan = subscriptionRepository.countByPlan(SubscriptionPlan.BASIC);
        long proPlan = subscriptionRepository.countByPlan(SubscriptionPlan.PRO);

        long activeSubscriptions = subscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE);
        long expiredSubscriptions = subscriptionRepository.countByStatus(SubscriptionStatus.EXPIRED);

        YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);
        Instant monthStart = thisMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthEnd = thisMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal revenueThisMonth = invoiceRepository.sumPaidAmountBetween(monthStart, monthEnd);
        if (revenueThisMonth == null) revenueThisMonth = BigDecimal.ZERO;

        Instant sixMonthsAgo = thisMonth.minusMonths(5).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Object[]> rows = invoiceRepository.monthlyRevenueSince(sixMonthsAgo);
        Map<String, BigDecimal> revenueByMonth = rows.stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (BigDecimal) r[1]));

        List<AdminStatsResponse.MonthlyRevenue> revenueLast6Months = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            String month = thisMonth.minusMonths(i).toString();
            revenueLast6Months.add(new AdminStatsResponse.MonthlyRevenue(
                    month, revenueByMonth.getOrDefault(month, BigDecimal.ZERO)));
        }

        return new AdminStatsResponse(
                totalBusinesses, totalUsers, activeUsers, pendingInvoices,
                freePlan, basicPlan, proPlan,
                activeSubscriptions, expiredSubscriptions,
                revenueThisMonth, revenueLast6Months
        );
    }
}
