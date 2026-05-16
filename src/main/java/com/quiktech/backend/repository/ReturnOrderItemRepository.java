package com.quiktech.backend.repository;

import com.quiktech.backend.entity.ReturnOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReturnOrderItemRepository extends JpaRepository<ReturnOrderItem, Long> {

    List<ReturnOrderItem> findByReturnOrderId(Long returnOrderId);

    void deleteByReturnOrderId(Long returnOrderId);
}

