package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.dashboard.DashboardResponse;
import com.quiktech.pos.dto.response.dashboard.DashboardResponse.*;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.CustomerRepository;
import com.quiktech.pos.repository.OrderRepository;
import com.quiktech.pos.repository.StoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashboardService {

    @PersistenceContext
    private EntityManager em;

    private final StoreRepository storeRepository;
    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(Long storeId) {
        Long businessId = storeRepository.findBusinessIdByStoreId(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));

        LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
        LocalDate lastMonth = thisMonth.minusMonths(1);
        LocalDate sixMonthsAgo = thisMonth.minusMonths(5);

        return new DashboardResponse(
                buildKpi(storeId, businessId, thisMonth, lastMonth),
                buildSalesChart(storeId, sixMonthsAgo),
                buildLowStock(businessId),
                buildRecentOrders(storeId)
        );
    }

    private KpiData buildKpi(Long storeId, Long businessId, LocalDate thisMonth, LocalDate lastMonth) {
        Object[] thisM = getMonthRow(storeId, thisMonth);
        Object[] lastM = getMonthRow(storeId, lastMonth);
        long customers = customerRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        return new KpiData(
                toBd(thisM[0]), toBd(lastM[0]),
                toBd(thisM[1]), toBd(lastM[1]),
                toL(thisM[2]), toL(lastM[2]),
                customers
        );
    }

    @SuppressWarnings("unchecked")
    private Object[] getMonthRow(Long storeId, LocalDate month) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT COALESCE(revenue, 0), COALESCE(collected, 0), COALESCE(order_count, 0) " +
                "FROM mv_monthly_revenue WHERE store_id = :storeId AND month = :month")
                .setParameter("storeId", storeId)
                .setParameter("month", month)
                .getResultList();
        return rows.isEmpty() ? new Object[]{BigDecimal.ZERO, BigDecimal.ZERO, 0L} : rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<SalesMonth> buildSalesChart(Long storeId, LocalDate fromMonth) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT TO_CHAR(month, 'YYYY-MM'), COALESCE(revenue, 0), COALESCE(order_count, 0) " +
                "FROM mv_monthly_revenue WHERE store_id = :storeId AND month >= :fromMonth ORDER BY month ASC")
                .setParameter("storeId", storeId)
                .setParameter("fromMonth", fromMonth)
                .getResultList();
        return rows.stream()
                .map(r -> new SalesMonth((String) r[0], toBd(r[1]), toL(r[2])))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<LowStockProduct> buildLowStock(Long businessId) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT product_name, sku, total_stock, min_stock_level " +
                "FROM mv_inventory_summary WHERE business_id = :businessId AND is_low_stock = true " +
                "ORDER BY total_stock ASC LIMIT 10")
                .setParameter("businessId", businessId)
                .getResultList();
        return rows.stream()
                .map(r -> new LowStockProduct((String) r[0], (String) r[1], toL(r[2]), toL(r[3])))
                .toList();
    }

    private List<RecentOrder> buildRecentOrders(Long storeId) {
        return orderRepository.findRecentByStoreId(storeId, PageRequest.of(0, 5)).stream()
                .map(o -> new RecentOrder(
                        o.getOrderCode(),
                        o.getCustomer() != null ? o.getCustomer().getName() : "Walk-in",
                        o.getTotalAmount(),
                        o.getStatus().name(),
                        o.getCreatedAt().toString()))
                .toList();
    }

    private BigDecimal toBd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        // Convert qua toString thay vì doubleValue() để không mất độ chính xác với số lớn
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    private long toL(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }
}
