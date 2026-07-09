package com.quiktech.backend.service;

import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.Subscription;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.exception.SubscriptionLimitExceededException;
import com.quiktech.backend.repository.OrderRepository;
import com.quiktech.backend.repository.ProductRepository;
import com.quiktech.backend.repository.StoreMemberRepository;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.SubscriptionRepository;
import com.quiktech.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SubscriptionLimitService {

    private final SubscriptionRepository subscriptionRepository;
    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final WarehouseRepository warehouseRepository;
    private final OrderRepository orderRepository;

    // ── Check limit tạo resource ──────────────────────────────────────────────
    // 4 check dưới đây khóa row subscription (SELECT ... FOR UPDATE qua
    // getSubscriptionForUpdate) để serialize mẫu check-then-insert: 2 request tạo
    // resource song song cùng business sẽ xếp hàng qua lock thay vì cùng đếm được
    // N < max rồi cùng insert vượt limit gói. Lock chỉ có tác dụng khi giữ tới lúc
    // caller commit → bắt buộc chạy trong transaction ghi của caller
    // (propagation = MANDATORY — fail sớm nếu bị gọi ngoài transaction).

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkStoreLimit(Long businessId) {
        Subscription sub = getSubscriptionForUpdate(businessId);
        if (sub.getMaxStores() == null) return;
        long count = storeRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        if (count >= sub.getMaxStores()) {
            throw new SubscriptionLimitExceededException("Store limit reached for your current plan");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkProductLimit(Long businessId) {
        Subscription sub = getSubscriptionForUpdate(businessId);
        if (sub.getMaxProducts() == null) return;
        long count = productRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        if (count >= sub.getMaxProducts()) {
            throw new SubscriptionLimitExceededException("Product limit reached for your current plan");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkStaffLimit(Long businessId) {
        Subscription sub = getSubscriptionForUpdate(businessId);
        if (sub.getMaxStaff() == null) return;
        long count = storeMemberRepository.countByBusinessId(businessId);
        if (count >= sub.getMaxStaff()) {
            throw new SubscriptionLimitExceededException("Staff limit reached for your current plan");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void checkWarehouseLimit(Long businessId) {
        Subscription sub = getSubscriptionForUpdate(businessId);
        if (sub.getMaxWarehouses() == null) return;
        long count = warehouseRepository.countByBusinessId(businessId);
        if (count >= sub.getMaxWarehouses()) {
            throw new SubscriptionLimitExceededException("Warehouse limit reached for your current plan");
        }
    }

    // checkOrderLimit KHÔNG dùng lock: nằm trên hot path tạo order (tần suất cao nhất
    // hệ thống) — khóa row subscription ở đây sẽ serialize toàn bộ order của business.
    // maxOrdersPerMonth hiện chưa từng được gán (luôn null, check return sớm) nên
    // chưa cần độ chính xác tuyệt đối như 4 limit trên.
    @Transactional(readOnly = true)
    public void checkOrderLimit(Long storeId) {
        Long businessId = storeRepository.findBusinessIdByStoreId(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
        Subscription sub = getSubscription(businessId);
        if (sub.getMaxOrdersPerMonth() == null) return;
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        Instant from = current.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = current.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        long count = orderRepository.countActiveByBusinessIdAndPeriod(businessId, from, to);
        if (count >= sub.getMaxOrdersPerMonth()) {
            throw new SubscriptionLimitExceededException("Monthly order limit reached for your current plan");
        }
    }

    private Subscription getSubscription(Long businessId) {
        return subscriptionRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Subscription not found"));
    }

    // SELECT ... FOR UPDATE — xem comment ở đầu nhóm check limit
    private Subscription getSubscriptionForUpdate(Long businessId) {
        return subscriptionRepository.findByBusinessIdForUpdate(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Subscription not found"));
    }
}
