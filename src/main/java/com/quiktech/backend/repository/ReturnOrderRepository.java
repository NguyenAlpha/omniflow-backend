package com.quiktech.backend.repository;

import com.quiktech.backend.entity.ReturnOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long> {

    Optional<ReturnOrder> findByStoreIdAndReturnCode(Long storeId, String returnCode);

    Optional<ReturnOrder> findByPublicId(UUID publicId);

    List<ReturnOrder> findByOriginalOrderId(Long originalOrderId);

    // Find with items (JOIN FETCH to avoid N+1)
    @Query("""
        SELECT DISTINCT ro FROM ReturnOrder ro
        LEFT JOIN FETCH ro.returnOrderItems roi
        WHERE ro.store.id = :storeId 
        AND ro.id = :returnOrderId
    """)
    Optional<ReturnOrder> findByIdWithItems(
        @Param("storeId") Long storeId,
        @Param("returnOrderId") Long returnOrderId
    );

    Page<ReturnOrder> findByStoreIdAndStatus(Long storeId, String status, Pageable pageable);

    long countByStoreIdAndStatus(Long storeId, String status);

    List<ReturnOrder> findByStoreIdOrderByCreatedAtDesc(Long storeId);

    @Query("""
        SELECT DISTINCT r FROM ReturnOrder r
        JOIN FETCH r.returnOrderItems ri JOIN FETCH ri.product
        WHERE r.publicId = :publicId
    """)
    Optional<ReturnOrder> findByPublicIdWithItems(@Param("publicId") UUID publicId);
}

