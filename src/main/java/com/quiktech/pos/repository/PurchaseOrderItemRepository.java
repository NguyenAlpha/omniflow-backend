package com.quiktech.pos.repository;

import com.quiktech.pos.entity.PurchaseOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, Long> {

    List<PurchaseOrderItem> findByPurchaseOrderIdAndDeletedAtIsNull(Long purchaseOrderId);

    long countByPurchaseOrderIdAndDeletedAtIsNull(Long purchaseOrderId);

    void deleteByPurchaseOrderId(Long purchaseOrderId);
}

