package com.quiktech.backend.service;

import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.Subscription;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.exception.SubscriptionLimitExceededException;
import com.quiktech.backend.repository.ProductRepository;
import com.quiktech.backend.repository.StoreMemberRepository;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.SubscriptionRepository;
import com.quiktech.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionLimitService {

    private final SubscriptionRepository subscriptionRepository;
    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final StoreMemberRepository storeMemberRepository;
    private final WarehouseRepository warehouseRepository;

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

    /**
     * Dùng cho import hàng loạt: trả về số product còn được phép tạo (null = không giới hạn).
     * Cũng khóa row subscription như các check trên để capacity ổn định đến khi caller commit.
     *
     * <p>Import KHÔNG được gọi {@code checkProductLimit} từng dòng giữa vòng lặp: khi vượt
     * limit, exception ném xuyên qua proxy {@code @Transactional} sẽ đánh dấu transaction
     * rollback-only dù caller có catch → toàn bộ import bị rollback và request trả 500
     * ({@code UnexpectedRollbackException}) thay vì báo lỗi từng dòng.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long getRemainingProductCapacity(Long businessId) {
        Subscription sub = getSubscriptionForUpdate(businessId);
        if (sub.getMaxProducts() == null) return null;
        long count = productRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        return Math.max(0, sub.getMaxProducts() - count);
    }

    // SELECT ... FOR UPDATE — xem comment ở đầu nhóm check limit
    private Subscription getSubscriptionForUpdate(Long businessId) {
        return subscriptionRepository.findByBusinessIdForUpdate(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Subscription not found"));
    }
}
