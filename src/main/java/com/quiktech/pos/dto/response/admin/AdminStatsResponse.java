package com.quiktech.pos.dto.response.admin;

import java.math.BigDecimal;
import java.util.List;

public record AdminStatsResponse(
        long totalBusinesses,
        long totalUsers,
        long activeUsers,
        long pendingInvoices,
        long freePlan,
        long basicPlan,
        long proPlan,
        long activeSubscriptions,
        long expiredSubscriptions,
        BigDecimal revenueThisMonth,
        List<MonthlyRevenue> revenueLast6Months
) {
    public record MonthlyRevenue(String month, BigDecimal amount) {}
}
