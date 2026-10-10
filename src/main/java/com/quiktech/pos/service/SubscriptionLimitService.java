package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.catalog.ProductLimitResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.entity.Subscription;
import com.quiktech.pos.entity.enums.RoleName;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.exception.SubscriptionLimitExceededException;
import com.quiktech.pos.repository.ProductRepository;
import com.quiktech.pos.repository.StoreMemberRepository;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.SubscriptionRepository;
import com.quiktech.pos.repository.UserRoleRepository;
import com.quiktech.pos.repository.WarehouseRepository;
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
    private final UserRoleRepository userRoleRepository;
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
        // Nhân sự = store member + trợ lý cấp business (BUSINESS_MANAGER); OWNER không tính
        long count = storeMemberRepository.countByBusinessId(businessId)
                + userRoleRepository.countActiveByBusinessAndRole(businessId, RoleName.ROLE_BUSINESS_MANAGER);
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

    /**
     * Pre-check cho UI trước khi mở form tạo product. Chỉ đọc, không khóa row:
     * kết quả có thể cũ đi khi tới lúc submit — {@code checkProductLimit} trong
     * create vẫn là chốt chặn thật.
     */
    @Transactional(readOnly = true)
    public ProductLimitResponse getProductLimit(Long businessId) {
        Subscription sub = subscriptionRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Subscription not found"));
        long count = productRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        boolean canCreate = sub.getMaxProducts() == null || count < sub.getMaxProducts();
        return new ProductLimitResponse(canCreate, count, sub.getMaxProducts());
    }

    // SELECT ... FOR UPDATE — xem comment ở đầu nhóm check limit
    private Subscription getSubscriptionForUpdate(Long businessId) {
        return subscriptionRepository.findByBusinessIdForUpdate(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Subscription not found"));
    }
}
