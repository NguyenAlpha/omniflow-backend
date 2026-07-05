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

    @Transactional(readOnly = true)
    public void checkStoreLimit(Long businessId) {
        Subscription sub = getSubscription(businessId);
        if (sub.getMaxStores() == null) return;
        long count = storeRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        if (count >= sub.getMaxStores()) {
            throw new SubscriptionLimitExceededException("Store limit reached for your current plan");
        }
    }

    @Transactional(readOnly = true)
    public void checkProductLimit(Long businessId) {
        Subscription sub = getSubscription(businessId);
        if (sub.getMaxProducts() == null) return;
        long count = productRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        if (count >= sub.getMaxProducts()) {
            throw new SubscriptionLimitExceededException("Product limit reached for your current plan");
        }
    }

    @Transactional(readOnly = true)
    public void checkStaffLimit(Long businessId) {
        Subscription sub = getSubscription(businessId);
        if (sub.getMaxStaff() == null) return;
        long count = storeMemberRepository.countByBusinessId(businessId);
        if (count >= sub.getMaxStaff()) {
            throw new SubscriptionLimitExceededException("Staff limit reached for your current plan");
        }
    }

    @Transactional(readOnly = true)
    public void checkWarehouseLimit(Long businessId) {
        Subscription sub = getSubscription(businessId);
        if (sub.getMaxWarehouses() == null) return;
        long count = warehouseRepository.countByBusinessId(businessId);
        if (count >= sub.getMaxWarehouses()) {
            throw new SubscriptionLimitExceededException("Warehouse limit reached for your current plan");
        }
    }

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
}
