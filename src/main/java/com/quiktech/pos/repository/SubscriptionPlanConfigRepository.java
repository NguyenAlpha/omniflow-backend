package com.quiktech.pos.repository;

import com.quiktech.pos.entity.SubscriptionPlanConfig;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SubscriptionPlanConfigRepository extends JpaRepository<SubscriptionPlanConfig, SubscriptionPlan> {

    /**
     * FOR SHARE — dùng khi chép limit của gói sang subscription. Admin sửa gói giữ
     * FOR UPDATE nên phải chờ transaction này commit; khi đó lệnh UPDATE lan truyền
     * limit mới của admin thấy được subscription vừa đổi gói → không sót bản sao cũ.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM SubscriptionPlanConfig p WHERE p.code = :code")
    Optional<SubscriptionPlanConfig> findForShare(@Param("code") SubscriptionPlan code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SubscriptionPlanConfig p WHERE p.code = :code")
    Optional<SubscriptionPlanConfig> findForUpdate(@Param("code") SubscriptionPlan code);
}
