package com.quiktech.pos.dto.response.dashboard;

import java.math.BigDecimal;
import java.util.List;

public record DashboardResponse(
    KpiData kpi,
    List<SalesMonth> salesChart,
    List<LowStockProduct> lowStockProducts,
    List<RecentOrder> recentOrders
) {
    public record KpiData(
        BigDecimal revenueThisMonth,
        BigDecimal revenueLastMonth,
        BigDecimal collectedThisMonth,
        BigDecimal collectedLastMonth,
        long ordersThisMonth,
        long ordersLastMonth,
        long totalCustomers
    ) {}

    public record SalesMonth(String month, BigDecimal revenue, long orderCount) {}

    public record LowStockProduct(String productName, String sku, long totalStock, long minStockLevel) {}

    public record RecentOrder(String orderCode, String customerName, BigDecimal totalAmount, String status, String createdAt) {}
}
