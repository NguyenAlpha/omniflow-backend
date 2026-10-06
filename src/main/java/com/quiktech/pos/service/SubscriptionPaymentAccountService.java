package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.subscription.PaymentAccountRequest;
import com.quiktech.pos.dto.request.subscription.PaymentAccountActionRequest;
import com.quiktech.pos.dto.response.admin.PaymentAccountResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.subscription.BankTransferInfoResponse;
import com.quiktech.pos.entity.SubscriptionInvoice;
import com.quiktech.pos.entity.SubscriptionPaymentAccount;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.exception.PaymentAccountUnavailableException;
import com.quiktech.pos.repository.SubscriptionPaymentAccountRepository;
import com.quiktech.pos.repository.SubscriptionPaymentSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SubscriptionPaymentAccountService {
    private final SubscriptionPaymentAccountRepository accounts;
    private final SubscriptionPaymentSettingsRepository settings;
    private final AdminAuditService audit;

    @Transactional(readOnly = true)
    public List<PaymentAccountResponse> list() {
        Long activeId = settings.findById(1).orElseThrow().getActiveAccountId();
        return accounts.findAllByOrderByArchivedAscCreatedAtDescIdDesc().stream().map(a -> response(a, activeId)).toList();
    }

    @Transactional
    public PaymentAccountResponse create(PaymentAccountRequest request) {
        var config = settings.lockSettings();
        if (accounts.existsByBankNameIgnoreCaseAndAccountNumberAndArchivedFalse(request.bankName().trim(), request.accountNumber())) {
            throw new IllegalArgumentException("This bank account already exists");
        }
        var account = new SubscriptionPaymentAccount();
        apply(account, request);
        accounts.saveAndFlush(account);
        var result = response(account, config.getActiveAccountId());
        audit.record("ADMIN_PAYMENT_ACCOUNT_CREATED", "PAYMENT_ACCOUNT", account.getId(), null, request.reason(), Map.of(), result);
        return result;
    }

    @Transactional
    public PaymentAccountResponse update(Long id, PaymentAccountRequest request) {
        var config = settings.lockSettings();
        var account = editable(id, request.version());
        var before = response(account, config.getActiveAccountId());
        if (accounts.existsByBankNameIgnoreCaseAndAccountNumberAndArchivedFalseAndIdNot(request.bankName().trim(), request.accountNumber(), id)) {
            throw new IllegalArgumentException("This bank account already exists");
        }
        apply(account, request);
        accounts.saveAndFlush(account);
        var result = response(account, config.getActiveAccountId());
        audit.record("ADMIN_PAYMENT_ACCOUNT_UPDATED", "PAYMENT_ACCOUNT", id, null, request.reason(), before, result);
        return result;
    }

    @Transactional
    public PaymentAccountResponse activate(Long id, PaymentAccountActionRequest request) {
        var config = settings.lockSettings();
        var account = editable(id, request.version());
        Long previousId = config.getActiveAccountId();
        if (Objects.equals(previousId, id)) return response(account, id);
        Object before = previousId == null ? Map.of() : response(find(previousId), previousId);
        config.setActiveAccountId(id);
        var result = response(account, id);
        audit.record("ADMIN_PAYMENT_ACCOUNT_ACTIVATED", "PAYMENT_ACCOUNT", id, null, request.reason(), before, result);
        return result;
    }

    @Transactional
    public PaymentAccountResponse archive(Long id, PaymentAccountActionRequest request) {
        var config = settings.lockSettings();
        var account = editable(id, request.version());
        if (Objects.equals(config.getActiveAccountId(), id)) {
            throw new IllegalArgumentException("Choose another receiving account before archiving the active account");
        }
        var before = response(account, config.getActiveAccountId());
        account.setArchived(true);
        accounts.saveAndFlush(account);
        var result = response(account, config.getActiveAccountId());
        audit.record("ADMIN_PAYMENT_ACCOUNT_ARCHIVED", "PAYMENT_ACCOUNT", id, null, request.reason(), before, result);
        return result;
    }

    @Transactional(readOnly = true)
    public BankTransferInfoResponse currentBankInfo() {
        Long id = settings.findById(1).orElseThrow().getActiveAccountId();
        return id == null ? null : bankInfo(find(id));
    }

    // Copy under the same lock used by edits/switches, inside the invoice transaction.
    @Transactional
    public void snapshotInto(SubscriptionInvoice invoice) {
        Long id = settings.lockSettings().getActiveAccountId();
        if (id == null) throw new PaymentAccountUnavailableException();
        var account = find(id);
        if (account.isArchived()) throw new PaymentAccountUnavailableException();
        invoice.setPaymentAccountId(id);
        invoice.setPaymentBankName(account.getBankName());
        invoice.setPaymentAccountNumber(account.getAccountNumber());
        invoice.setPaymentAccountHolder(account.getAccountHolder());
        invoice.setPaymentBranch(account.getBranch());
    }

    private SubscriptionPaymentAccount editable(Long id, Long version) {
        var account = find(id);
        if (account.isArchived()) throw new IllegalArgumentException("Archived accounts cannot be changed or selected");
        if (version == null || !version.equals(account.getVersion())) {
            throw new OptimisticLockingFailureException("Account changed; reload before continuing");
        }
        return account;
    }

    private SubscriptionPaymentAccount find(Long id) {
        return accounts.findById(id).orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PAYMENT_ACCOUNT_NOT_FOUND, "Receiving account not found"));
    }

    private void apply(SubscriptionPaymentAccount account, PaymentAccountRequest request) {
        account.setLabel(request.label().trim());
        account.setBankName(request.bankName().trim());
        account.setAccountNumber(request.accountNumber());
        account.setAccountHolder(request.accountHolder().trim());
        account.setBranch(request.branch() == null ? "" : request.branch().trim());
    }

    private BankTransferInfoResponse bankInfo(SubscriptionPaymentAccount a) {
        return new BankTransferInfoResponse(a.getBankName(), a.getAccountNumber(), a.getAccountHolder(), a.getBranch());
    }

    private PaymentAccountResponse response(SubscriptionPaymentAccount a, Long activeId) {
        return new PaymentAccountResponse(a.getId(), a.getLabel(), a.getBankName(), a.getAccountNumber(),
                a.getAccountHolder(), a.getBranch(), Objects.equals(a.getId(), activeId), a.isArchived(),
                a.getVersion(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
