package com.quiktech.pos.repository;

import com.quiktech.pos.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    List<PriceHistory> findByProductIdOrderByChangedAtDesc(Long productId);

    List<PriceHistory> findByBusinessIdOrderByChangedAtDesc(Long businessId);

    @Query("""
        SELECT ph FROM PriceHistory ph
        WHERE ph.product.id = :productId
        AND ph.changedAt BETWEEN :startDate AND :endDate
        ORDER BY ph.changedAt DESC
    """)
    List<PriceHistory> findByProductAndDateRange(
        @Param("productId") Long productId,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}
