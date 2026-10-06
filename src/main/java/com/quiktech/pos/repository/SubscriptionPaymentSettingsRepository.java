package com.quiktech.pos.repository;

import com.quiktech.pos.entity.SubscriptionPaymentSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface SubscriptionPaymentSettingsRepository extends JpaRepository<SubscriptionPaymentSettings, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SubscriptionPaymentSettings s WHERE s.id = 1")
    SubscriptionPaymentSettings lockSettings();
}
