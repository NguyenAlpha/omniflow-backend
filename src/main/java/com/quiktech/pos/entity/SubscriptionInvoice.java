package com.quiktech.pos.entity;

import com.quiktech.pos.entity.enums.BillingCycle;
import com.quiktech.pos.entity.enums.InvoiceStatus;
import com.quiktech.pos.entity.enums.SubscriptionPlan;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "subscription_invoices", indexes = {
    @Index(name = "idx_sub_invoices_business_id", columnList = "business_id"),
    @Index(name = "idx_sub_invoices_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingCycle billingCycle;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    @Column(length = 20)
    private String paymentMethod; // BANK_TRANSFER, CARD, MOMO, ...

    // Mã/nội dung chuyển khoản do business owner gửi lên để admin đối chiếu
    @Column(length = 100)
    private String bankTransferRef;

    // Immutable receiving details copied at invoice creation; never resolve them from the current default.
    private Long paymentAccountId;
    @Column(length = 150)
    private String paymentBankName;
    @Column(length = 50)
    private String paymentAccountNumber;
    @Column(length = 150)
    private String paymentAccountHolder;
    @Column(length = 150)
    private String paymentBranch;
    @Column(length = 40)
    private String paymentQrImageKey;

    // ID của admin user đã xác nhận thanh toán
    @Column(name = "confirmed_by")
    private Long confirmedBy;

    // Ghi chú của admin khi confirm hoặc reject
    @Column(columnDefinition = "TEXT")
    private String adminNote;

    @Column(columnDefinition = "TIMESTAMPTZ")
    private Instant confirmedAt;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant periodStart;

    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant periodEnd;

    @Column(columnDefinition = "TIMESTAMPTZ")
    private Instant paidAt;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
