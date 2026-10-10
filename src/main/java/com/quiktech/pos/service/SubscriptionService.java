package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.subscription.BankTransferInfoResponse;
import com.quiktech.pos.dto.response.subscription.SubscriptionInvoiceResponse;
import com.quiktech.pos.dto.response.subscription.SubscriptionResponse;
import com.quiktech.pos.dto.response.subscription.UpgradeResponse;
import com.quiktech.pos.entity.Subscription;
import com.quiktech.pos.entity.SubscriptionInvoice;
import com.quiktech.pos.entity.SubscriptionPlanConfig;
import com.quiktech.pos.entity.enums.BillingCycle;
import com.quiktech.pos.entity.enums.InvoiceStatus;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import com.quiktech.pos.entity.enums.SubscriptionStatus;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.SubscriptionInvoiceRepository;
import com.quiktech.pos.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import jakarta.persistence.criteria.Predicate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanCatalogService planCatalogService;
    private final SubscriptionInvoiceRepository invoiceRepository;
    private final EmailService emailService;
    private final AdminAuditService adminAuditService;

    private final SubscriptionPaymentAccountService paymentAccounts;
    private final PaymentQrStorage qrStorage;

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

        // Chỉ cần pendingPlan, không set pendingBillingCycle: downgrade về gói trả phí
        // thấp hơn hiện chuyển sub sang EXPIRED tại cuối chu kỳ (user re-subscribe và
        // chọn chu kỳ ở luồng upgrade), còn FREE không có chu kỳ — field này chỉ dùng
        // khi nào có luồng downgrade giữ ACTIVE kèm thanh toán.
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

        BigDecimal amount = planCatalogService.priceFor(newPlan, billingCycle);
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

        paymentAccounts.snapshotInto(invoice);
        SubscriptionInvoice saved = invoiceRepository.save(invoice);
        saved.setBankTransferRef(newPlan.name().toLowerCase() + " " + saved.getId());
        return new UpgradeResponse(toInvoiceResponse(invoiceRepository.save(saved)), invoiceBankInfo(saved));
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
        return paymentAccounts.currentBankInfo();
    }

    @Transactional(readOnly = true)
    public BankTransferInfoResponse getInvoiceBankTransferInfo(Long businessId, Long invoiceId) {
        return invoiceBankInfo(getInvoiceForBusiness(businessId, invoiceId));
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> getInvoiceQr(Long businessId, Long invoiceId) {
        return qrStorage.image(getInvoiceForBusiness(businessId, invoiceId).getPaymentQrImageKey());
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
        return changePlan(businessId, newPlan, billingCycle, null);
    }

    @PreAuthorize("hasRole('ROLE_SUPER_ADMIN')")
    @Transactional
    public SubscriptionResponse changePlan(Long businessId, SubscriptionPlan newPlan, BillingCycle billingCycle, String reason) {
        Subscription sub = getSubscriptionForUpdate(businessId);
        var before = toResponse(sub);
        SubscriptionPlanConfig limits = planCatalogService.limitsFor(newPlan);
        Instant now = Instant.now();

        sub.setPlan(newPlan);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        limits.applyLimitsTo(sub);
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
        // Chu kỳ mới bắt đầu — cho phép gửi lại email cảnh báo khi chu kỳ này sắp hết hạn
        sub.setExpiryWarningSentAt(null);

        var after = toResponse(subscriptionRepository.save(sub));
        adminAuditService.record("ADMIN_PLAN_CHANGED", "SUBSCRIPTION", sub.getId(), businessId, reason, before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionInvoiceResponse> listPendingInvoices(Pageable pageable) {
        return invoiceRepository.findByStatusOrderByCreatedAtDesc(InvoiceStatus.PENDING, pageable)
                .map(this::toInvoiceResponse);
    }

    @Transactional(readOnly = true)
    public long countPendingInvoices() {
        return invoiceRepository.countByStatus(InvoiceStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionInvoiceResponse> searchInvoices(InvoiceStatus status, Long businessId, String query, Pageable pageable) {
        String search = query == null ? "" : query.trim();
        if (search.length() > 100 || (businessId != null && businessId <= 0)) {
            throw new IllegalArgumentException("Invalid invoice search");
        }
        var ordered = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return invoiceRepository.findAll((root, criteria, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            if (status != null) filters.add(cb.equal(root.get("status"), status));
            if (businessId != null) filters.add(cb.equal(root.get("business").get("id"), businessId));
            if (!search.isEmpty()) {
                String pattern = "%" + search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                List<Predicate> matches = new ArrayList<>();
                matches.add(cb.like(cb.lower(root.get("business").get("name")), pattern, '!'));
                matches.add(cb.like(cb.lower(root.get("bankTransferRef")), pattern, '!'));
                try {
                    Long id = Long.valueOf(search);
                    matches.add(cb.equal(root.get("id"), id));
                    matches.add(cb.equal(root.get("business").get("id"), id));
                } catch (NumberFormatException ignored) {
                    // Text searches still match business names and transfer references.
                }
                filters.add(cb.or(matches.toArray(Predicate[]::new)));
            }
            return cb.and(filters.toArray(Predicate[]::new));
        }, ordered).map(this::toInvoiceResponse);
    }

    /**
     * Admin xác nhận thanh toán chuyển khoản → kích hoạt subscription lên plan mới.
     */
    @Transactional
    public SubscriptionInvoiceResponse confirmInvoice(Long invoiceId, Long confirmedByUserId, String adminNote) {
        SubscriptionInvoice invoice = getInvoiceForUpdate(invoiceId);
        var before = toInvoiceResponse(invoice);

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
        Subscription sub = getSubscriptionForUpdate(invoice.getBusiness().getId());
        var beforeSubscription = toResponse(sub);
        SubscriptionPlanConfig limits = planCatalogService.limitsFor(invoice.getPlan());
        sub.setPlan(invoice.getPlan());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setBillingCycle(invoice.getBillingCycle());
        limits.applyLimitsTo(sub);
        sub.setStartedAt(now);
        sub.setExpiresAt(invoice.getPeriodEnd());
        // Chu kỳ mới bắt đầu — cho phép gửi lại email cảnh báo khi chu kỳ này sắp hết hạn
        sub.setExpiryWarningSentAt(null);
        subscriptionRepository.save(sub);

        adminAuditService.record("ADMIN_INVOICE_CONFIRMED", "SUBSCRIPTION_INVOICE", invoiceId, invoice.getBusiness().getId(),
                adminNote, java.util.Map.of("invoice", before, "subscription", beforeSubscription),
                java.util.Map.of("invoice", toInvoiceResponse(invoice), "subscription", toResponse(sub)));

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
        SubscriptionInvoice invoice = getInvoiceForUpdate(invoiceId);
        var before = toInvoiceResponse(invoice);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new IllegalArgumentException("Invoice is already paid");
        }
        if (invoice.getStatus() == InvoiceStatus.FAILED) {
            throw new IllegalArgumentException("Invoice is already rejected");
        }

        invoice.setStatus(InvoiceStatus.FAILED);
        invoice.setAdminNote(adminNote);
        SubscriptionInvoice saved = invoiceRepository.save(invoice);
        adminAuditService.record("ADMIN_INVOICE_REJECTED", "SUBSCRIPTION_INVOICE", invoiceId,
                invoice.getBusiness().getId(), adminNote, before, toInvoiceResponse(saved));

        emailService.sendInvoiceRejected(
                saved.getBusiness().getEmail(),
                saved.getBusiness().getName(),
                adminNote);

        return toInvoiceResponse(saved);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Subscription getSubscriptionForUpdate(Long businessId) {
        return subscriptionRepository.findByBusinessIdForUpdate(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "Subscription not found"));
    }

    private SubscriptionInvoice getInvoiceForUpdate(Long invoiceId) {
        return invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.INVOICE_NOT_FOUND, "Invoice not found"));
    }

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

    private BankTransferInfoResponse invoiceBankInfo(SubscriptionInvoice invoice) {
        if (invoice.getPaymentBankName() == null) return null;
        return new BankTransferInfoResponse(invoice.getPaymentBankName(), invoice.getPaymentAccountNumber(),
                invoice.getPaymentAccountHolder(), invoice.getPaymentBranch(),
                invoice.getPaymentQrImageKey() == null ? null : "/api/businesses/" + invoice.getBusiness().getId()
                        + "/subscription/invoices/" + invoice.getId() + "/qr");
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
                invoice.getUpdatedAt(),
                invoice.getPaymentAccountId(),
                invoiceBankInfo(invoice)
        );
    }
}
