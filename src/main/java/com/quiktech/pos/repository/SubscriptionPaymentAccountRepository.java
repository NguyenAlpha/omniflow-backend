package com.quiktech.pos.repository;

import com.quiktech.pos.entity.SubscriptionPaymentAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SubscriptionPaymentAccountRepository extends JpaRepository<SubscriptionPaymentAccount, Long> {
    List<SubscriptionPaymentAccount> findAllByOrderByArchivedAscCreatedAtDescIdDesc();
    boolean existsByBankNameIgnoreCaseAndAccountNumberAndArchivedFalse(String bankName, String accountNumber);
    boolean existsByBankNameIgnoreCaseAndAccountNumberAndArchivedFalseAndIdNot(String bankName, String accountNumber, Long id);
}
