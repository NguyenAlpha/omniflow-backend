package com.quiktech.backend.service;

import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.subscription.BankTransferInfoResponse;
import com.quiktech.backend.dto.response.subscription.SubscriptionInvoiceResponse;
import com.quiktech.backend.dto.response.subscription.SubscriptionResponse;
import com.quiktech.backend.dto.response.subscription.UpgradeResponse;
import com.quiktech.backend.entity.Subscription;
import com.quiktech.backend.entity.SubscriptionInvoice;
import com.quiktech.backend.entity.enums.BillingCycle;
import com.quiktech.backend.entity.enums.InvoiceStatus;
import com.quiktech.backend.entity.enums.PlanLimits;
import com.quiktech.backend.entity.enums.PlanPricing;
import com.quiktech.backend.entity.enums.SubscriptionPlan;
import com.quiktech.backend.entity.enums.SubscriptionStatus;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.SubscriptionInvoiceRepository;
import com.quiktech.backend.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final EmailService emailService;

    @Value("${subscription.payment.bank-name:}")
    private String bankName;

    @Value("${subscription.payment.account-number:}")
    private String bankAccountNumber;

    @Value("${subscription.payment.account-holder:}")
    private String bankAccountHolder;

    @Value("${subscription.payment.branch:}")
    private String bankBranch;

    // ── Business owner ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SubscriptionResponse get(Long businessId) {
        return toResponse(getSubscription(businessId));
    }

    /**
     * Business owner đặt lịch downgrade về gói thấp hơn, hiệu lực cuối chu kỳ hiện tại.
     * Không hoàn tiền. Dữ liệu hiện có không bị xóa (soft cap).
     */
    @Transactional
    public SubscriptionResponse scheduleDowngrade(Long businessId, SubscriptionPlan newPlan) {
        Subscription sub = getSubscription(businessId);

        if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new IllegalArgumentException("Subscription is not active");
        }
        if (sub.getPlan() == SubscriptionPlan.FREE) {
            throw new IllegalArgumentException("Already on the FREE plan");
        }
        if (newPlan.ordinal() >= sub.getPlan().ordinal()) {
            throw new IllegalArgumentException("Target plan must be lower than current plan");
        }

        sub.setPendingPlan(newPlan);
        return toResponse(subscriptionRepository.save(sub));
    }

    /**
     * Business owner huỷ lịch downgrade đã đặt trước.
     */
    @Transactional
    public SubscriptionResponse cancelScheduledDowngrade(Long businessId) {
        Subscription sub = getSubscription(businessId);

        if (sub.getPendingPlan() == null) {
            throw new IllegalArgumentException("No scheduled downgrade to cancel");
        }

        sub.setPendingPlan(null);
        sub.setPendingBillingCycle(null);
        return toResponse(subscriptionRepository.save(sub));
    }

    /**
     * Business owner tạo yêu cầu nâng cấp gói.
     * Tạo invoice PENDING + trả về thông tin tài khoản ngân hàng để chuyển khoản.
     * Sub ACTIVE: chỉ cho phép nâng lên gói cao hơn gói hiện tại.
     * Sub không còn ACTIVE (EXPIRED): cho phép mua lại bất kỳ gói trả phí nào
     * (renewal/re-subscribe) — plan trên sub lúc này chỉ là record lịch sử.
     * Không cho phép tạo khi đã có invoice PENDING.
     */
    @Transactional
    public UpgradeResponse requestUpgrade(Long businessId, SubscriptionPlan newPlan, BillingCycle billingCycle) {
        Subscription sub = getSubscription(businessId);

        // FREE không có gì để thanh toán — chặn tường minh. Trước đây FREE bị chặn
        // gián tiếp qua so sánh ordinal, nhưng với sub EXPIRED thì check ordinal
        // bên dưới được bỏ qua nên cần guard riêng.
        if (newPlan == SubscriptionPlan.FREE) {
            throw new IllegalArgumentException("Cannot request an upgrade to the FREE plan");
        }

        // Điều kiện "gói mới phải cao hơn" chỉ áp dụng khi sub còn ACTIVE.
        // Sub EXPIRED được mua lại gói bằng/thấp hơn plan cũ (VD: PRO hết hạn mua lại
        // chính PRO hoặc BASIC) — nếu vẫn áp check ordinal, user PRO hết hạn sẽ kẹt
        // vĩnh viễn: không còn gói nào cao hơn để mua, không có đường thanh toán nào.
        if (sub.getStatus() == SubscriptionStatus.ACTIVE && newPlan.ordinal() <= sub.getPlan().ordinal()) {
            throw new IllegalArgumentException("New plan must be higher than current plan");
        }

        // Check-then-act — 2 request song song vẫn có thể cùng qua check này;
        // backstop DB: ux_subscription_invoices_pending (V9) chặn business có 2 invoice
        // PENDING cùng lúc (request thứ hai fail khi INSERT)
        List<SubscriptionInvoice> pending = invoiceRepository.findPendingByBusinessId(businessId, InvoiceStatus.PENDING);
        if (!pending.isEmpty()) {
            throw new IllegalArgumentException("A pending invoice already exists for this business");
        }

        BigDecimal amount = PlanPricing.valueOf(newPlan.name()).priceFor(billingCycle);
        Instant now = Instant.now();
        Instant periodEnd = billingCycle == BillingCycle.YEARLY
                ? now.plus(365, ChronoUnit.DAYS)
                : now.plus(30, ChronoUnit.DAYS);

        SubscriptionInvoice invoice = SubscriptionInvoice.builder()
                .business(sub.getBusiness())
                .plan(newPlan)
                .billingCycle(billingCycle)
                .amount(amount)
                .status(InvoiceStatus.PENDING)
                .periodStart(now)
                .periodEnd(periodEnd)
                .build();

        SubscriptionInvoice saved = invoiceRepository.save(invoice);
        saved.setBankTransferRef(newPlan.name().toLowerCase() + " " + saved.getId());
        return new UpgradeResponse(toInvoiceResponse(invoiceRepository.save(saved)), getBankInfo());
    }

    /**
     * Business owner gửi mã/nội dung chuyển khoản để admin đối chiếu.
     */
    @Transactional
    public SubscriptionInvoiceResponse submitPaymentRef(Long businessId, Long invoiceId, String bankTransferRef) {
        SubscriptionInvoice invoice = getInvoiceForBusiness(businessId, invoiceId);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new IllegalArgumentException("Invoice is already paid");
        }
        if (invoice.getStatus() == InvoiceStatus.FAILED) {
            throw new IllegalArgumentException("Invoice has been rejected and cannot be updated");
        }

        invoice.setBankTransferRef(bankTransferRef);
        return toInvoiceResponse(invoiceRepository.save(invoice));
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionInvoiceResponse> getInvoices(Long businessId, Pageable pageable) {
        return invoiceRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable)
                .map(this::toInvoiceResponse);
    }

    @Transactional(readOnly = true)
    public SubscriptionInvoiceResponse getInvoice(Long businessId, Long invoiceId) {
        return toInvoiceResponse(getInvoiceForBusiness(businessId, invoiceId));
    }

    /**
     * Business owner huỷ invoice PENDING — chỉ được phép khi invoice chưa được admin xử lý.
     */
    @Transactional
    public void cancelInvoice(Long businessId, Long invoiceId) {
        SubscriptionInvoice invoice = getInvoiceForBusiness(businessId, invoiceId);
        if (invoice.getStatus() != InvoiceStatus.PENDING) {
            throw new IllegalArgumentException("Only PENDING invoices can be cancelled");
        }
        invoiceRepository.delete(invoice);
    }

    @Transactional(readOnly = true)
    public BankTransferInfoResponse getBankTransferInfo() {
        return getBankInfo();
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    /**
     * Admin override trực tiếp plan (không qua invoice flow) — dùng cho điều chỉnh thủ công.
     * FREE: không có thời hạn → expiresAt/billingCycle = null.
     * Gói trả phí: bắt buộc truyền billingCycle để tính expiresAt mới — nếu giữ
     * expiresAt cũ (có thể đã ở quá khứ) scheduler sẽ expire ngay lần chạy kế tiếp,
     * còn nếu để null thì gói trả phí không bao giờ hết hạn.
     */
    @PreAuthorize("hasRole('ROLE_SUPER_ADMIN')")
    @Transactional
    public SubscriptionResponse changePlan(Long businessId, SubscriptionPlan newPlan, BillingCycle billingCycle) {
        Subscription sub = getSubscription(businessId);
        PlanLimits limits = PlanLimits.valueOf(newPlan.name());
        Instant now = Instant.now();

        sub.setPlan(newPlan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setMaxStores(limits.maxStores);
        sub.setMaxStaff(limits.maxStaff);
        sub.setMaxProducts(limits.maxProducts);
        sub.setMaxWarehouses(limits.maxWarehouses);
        sub.setStartedAt(now);

        if (newPlan == SubscriptionPlan.FREE) {
            sub.setExpiresAt(null);
            sub.setBillingCycle(null);
        } else {
            if (billingCycle == null) {
                throw new IllegalArgumentException("billingCycle is required when changing to a paid plan");
            }
            sub.setBillingCycle(billingCycle);
            sub.setExpiresAt(billingCycle == BillingCycle.YEARLY
                    ? now.plus(365, ChronoUnit.DAYS)
                    : now.plus(30, ChronoUnit.DAYS));
        }

        // Quyết định thủ công của admin thay thế mọi lịch downgrade đã đặt trước —
        // nếu không clear, scheduler sẽ áp pendingPlan cũ đè lên plan admin vừa set
        sub.setPendingPlan(null);
        sub.setPendingBillingCycle(null);

        return toResponse(subscriptionRepository.save(sub));
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionInvoiceResponse> listPendingInvoices(Pageable pageable) {
        return invoiceRepository.findByStatusOrderByCreatedAtDesc(InvoiceStatus.PENDING, pageable)
                .map(this::toInvoiceResponse);
    }

    /**
     * Admin xác nhận thanh toán chuyển khoản → kích hoạt subscription lên plan mới.
     */
    @Transactional
    public SubscriptionInvoiceResponse confirmInvoice(Long invoiceId, Long confirmedByUserId, String adminNote) {
        SubscriptionInvoice invoice = getInvoiceById(invoiceId);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new IllegalArgumentException("Invoice is already paid");
        }
        if (invoice.getStatus() == InvoiceStatus.FAILED) {
            throw new IllegalArgumentException("Invoice is already rejected");
        }

        Instant now = Instant.now();

        // Kỳ sử dụng tính lại từ thời điểm admin CONFIRM, không dùng periodStart/periodEnd
        // đã chốt lúc user tạo request — nếu admin confirm trễ N ngày mà vẫn dùng
        // periodEnd cũ, user mất N ngày sử dụng đã trả tiền.
        Instant periodEnd = invoice.getBillingCycle() == BillingCycle.YEARLY
                ? now.plus(365, ChronoUnit.DAYS)
                : now.plus(30, ChronoUnit.DAYS);
        invoice.setPeriodStart(now);
        invoice.setPeriodEnd(periodEnd);

        invoice.setStatus(InvoiceStatus.PAID);
        invoice.setPaymentMethod("BANK_TRANSFER");
        invoice.setPaidAt(now);
        invoice.setConfirmedBy(confirmedByUserId);
        invoice.setConfirmedAt(now);
        invoice.setAdminNote(adminNote);
        invoiceRepository.save(invoice);

        // Cập nhật subscription sang plan mới
        Subscription sub = getSubscription(invoice.getBusiness().getId());
        PlanLimits limits = PlanLimits.valueOf(invoice.getPlan().name());
        sub.setPlan(invoice.getPlan());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setBillingCycle(invoice.getBillingCycle());
        sub.setMaxStores(limits.maxStores);
        sub.setMaxStaff(limits.maxStaff);
        sub.setMaxProducts(limits.maxProducts);
        sub.setMaxWarehouses(limits.maxWarehouses);
        sub.setStartedAt(now);
        sub.setExpiresAt(invoice.getPeriodEnd());
        subscriptionRepository.save(sub);

        emailService.sendInvoiceConfirmed(
                invoice.getBusiness().getEmail(),
                invoice.getBusiness().getName(),
                invoice.getPlan(),
                invoice.getAmount(),
                invoice.getPeriodEnd());

        return toInvoiceResponse(invoice);
    }

    /**
     * Admin từ chối thanh toán — invoice chuyển sang FAILED, subscription không thay đổi.
     */
    @Transactional
    public SubscriptionInvoiceResponse rejectInvoice(Long invoiceId, String adminNote) {
        SubscriptionInvoice invoice = getInvoiceById(invoiceId);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new IllegalArgumentException("Invoice is already paid");
        }
        if (invoice.getStatus() == InvoiceStatus.FAILED) {
            throw new IllegalArgumentException("Invoice is already rejected");
        }

        invoice.setStatus(InvoiceStatus.FAILED);
        invoice.setAdminNote(adminNote);
        SubscriptionInvoice saved = invoiceRepository.save(invoice);

        emailService.sendInvoiceRejected(
                saved.getBusiness().getEmail(),
                saved.getBusiness().getName(),
                adminNote);

        return toInvoiceResponse(saved);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Subscription getSubscription(Long businessId) {
        return subscriptionRepository.findByBusinessId(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Subscription not found"));
    }

    private SubscriptionInvoice getInvoiceById(Long invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.INVOICE_NOT_FOUND, "Invoice not found"));
    }

    private SubscriptionInvoice getInvoiceForBusiness(Long businessId, Long invoiceId) {
        SubscriptionInvoice invoice = getInvoiceById(invoiceId);
        if (!invoice.getBusiness().getId().equals(businessId)) {
            throw new ResourceNotFoundException(ErrorCode.INVOICE_NOT_FOUND, "Invoice not found");
        }
        return invoice;
    }

    private BankTransferInfoResponse getBankInfo() {
        return new BankTransferInfoResponse(bankName, bankAccountNumber, bankAccountHolder, bankBranch);
    }

    private SubscriptionResponse toResponse(Subscription sub) {
        return new SubscriptionResponse(
                sub.getId(),
                sub.getBusiness().getId(),
                sub.getPlan(),
                sub.getStatus(),
                sub.getBillingCycle(),
                sub.getMaxStores(),
                sub.getMaxStaff(),
                sub.getMaxProducts(),
                sub.getMaxWarehouses(),
                sub.getStartedAt(),
                sub.getExpiresAt(),
                sub.getCreatedAt(),
                sub.getUpdatedAt(),
                sub.getPendingPlan(),
                sub.getPendingBillingCycle()
        );
    }

    private SubscriptionInvoiceResponse toInvoiceResponse(SubscriptionInvoice invoice) {
        return new SubscriptionInvoiceResponse(
                invoice.getId(),
                invoice.getBusiness().getId(),
                invoice.getPlan(),
                invoice.getBillingCycle(),
                invoice.getAmount(),
                invoice.getStatus(),
                invoice.getBankTransferRef(),
                invoice.getAdminNote(),
                invoice.getPeriodStart(),
                invoice.getPeriodEnd(),
                invoice.getPaidAt(),
                invoice.getConfirmedAt(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt()
        );
    }
}
