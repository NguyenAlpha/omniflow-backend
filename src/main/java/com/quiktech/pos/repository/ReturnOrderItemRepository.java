package com.quiktech.pos.repository;

import com.quiktech.pos.entity.ReturnOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ReturnOrderItemRepository extends JpaRepository<ReturnOrderItem, Long> {

    List<ReturnOrderItem> findByReturnOrderId(Long returnOrderId);

    void deleteByReturnOrderId(Long returnOrderId);
}

