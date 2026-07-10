package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.partner.SupplierPayRequest;
import com.quiktech.backend.dto.request.partner.SupplierUpsertRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.partner.SupplierResponse;
import com.quiktech.backend.annotation.Auditable;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.entity.enums.PaymentMethod;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final BusinessRepository businessRepository;
    private final StoreRepository storeRepository;
    private final PaymentRepository paymentRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SupplierResponse> list(Long businessId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return supplierRepository.findByBusinessIdAndDeletedAtIsNull(businessId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResult<SupplierResponse> search(Long businessId, String q, Pageable pageable, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return PagedResult.of(supplierRepository.searchSuppliers(businessId, q, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return toResponse(findSupplierOrThrow(businessId, publicId));
    }

    @Transactional
    public SupplierResponse create(Long businessId, SupplierUpsertRequest request, UserPrincipal currentUser) {
        Business business = findBusinessOrThrow(businessId);

        if (supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, request.code()).isPresent()) {
            throw new IllegalArgumentException("Supplier code already exists in this business");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Supplier supplier = Supplier.builder()
                .business(business)
                .code(request.code())
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .address(request.address())
                .publicId(UUID.randomUUID())
                .createdBy(userRef)
                .lastModifiedByUser(userRef)
                .build();

        return toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public SupplierResponse update(Long businessId, UUID publicId, SupplierUpsertRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Supplier supplier = findSupplierOrThrow(businessId, publicId);

        supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, request.code())
                .filter(s -> !s.getPublicId().equals(publicId))
                .ifPresent(s -> { throw new IllegalArgumentException("Supplier code already exists in this business"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());
        supplier.setCode(request.code());
        supplier.setName(request.name());
        supplier.setPhone(request.phone());
        supplier.setEmail(request.email());
        supplier.setAddress(request.address());
        supplier.setLastModifiedByUser(userRef);
        supplier.setLastModifiedAt(Instant.now());
        supplier.setUpdatedAt(Instant.now());

        return toResponse(supplierRepository.save(supplier));
    }

    @Auditable(action = "PAY_SUPPLIER_DEBT", entityType = "SUPPLIER")
    @Transactional
    public SupplierResponse pay(Long businessId, UUID publicId, SupplierPayRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        // PESSIMISTIC_WRITE: serialize các request pay đồng thời trên cùng supplier,
        // tránh 2 giao dịch cùng đọc một debtBalance rồi cùng trừ → double-payment
        Supplier supplier = supplierRepository.findByBusinessIdAndPublicIdForUpdate(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));

        BigDecimal amount = request.amount();
        if (amount.compareTo(supplier.getDebtBalance()) > 0) {
            throw new IllegalArgumentException("Payment amount exceeds supplier debt balance");
        }

        supplier.setDebtBalance(supplier.getDebtBalance().subtract(amount));
        supplier.setLastModifiedAt(Instant.now());
        supplier.setUpdatedAt(Instant.now());
        User userRef = userRepository.getReferenceById(currentUser.userId());
        supplier.setLastModifiedByUser(userRef);
        Supplier saved = supplierRepository.save(supplier);

        // Distribute payment across outstanding purchase orders (oldest first)
        BigDecimal remaining = amount;
        for (PurchaseOrder po : purchaseOrderRepository.findReceivedOrdersBySupplierWithDebt(saved.getId())) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal apply = remaining.min(po.getDebtAmount());
            po.setPaidAmount(po.getPaidAmount().add(apply));
            po.setDebtAmount(po.getDebtAmount().subtract(apply));
            po.setLastModifiedAt(Instant.now());
            po.setUpdatedAt(Instant.now());
            purchaseOrderRepository.save(po);
            remaining = remaining.subtract(apply);
        }

        // remaining > 0 nghĩa là debtBalance và tổng debtAmount của các purchase order
        // đã lệch từ trước — không nuốt im lặng, log warn để phát hiện lệch sổ tích lũy
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("Supplier debt payment: {} remaining after allocating across received purchase orders "
                    + "(supplierId={}, businessId={}) — debtBalance lệch với tổng debtAmount của purchase order",
                    remaining, saved.getId(), businessId);
        }

        // Client gửi storeId thì validate thuộc business và dùng; không gửi thì fallback
        // store đầu tiên như trước (tương thích client cũ) kèm warn — báo cáo thu chi
        // theo store có thể sai khi business nhiều store
        Store store;
        if (request.storeId() != null) {
            store = storeRepository.findByIdAndDeletedAtIsNull(request.storeId())
                    .filter(s -> s.getBusiness().getId().equals(businessId))
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
        } else {
            store = storeRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
            log.warn("Supplier debt payment without storeId: assigned to first store {} of business {}",
                    store.getId(), businessId);
        }

        PaymentMethod method = request.paymentMethod() != null ? request.paymentMethod() : PaymentMethod.CASH;
        paymentRepository.save(Payment.builder()
                .store(store)
                .supplier(saved)
                .amount(amount)
                .paymentMethod(method.name())
                .note("Supplier payment: " + saved.getName())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build());

        return toResponse(saved);
    }

    @Auditable(action = "DELETE_SUPPLIER", entityType = "SUPPLIER")
    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Supplier supplier = findSupplierOrThrow(businessId, publicId);

        // Chặn xóa khi còn công nợ: sau soft-delete, NCC biến mất khỏi
        // findSuppliersWithDebt và mọi báo cáo công nợ (filter deleted_at IS NULL)
        // → khoản nợ "bốc hơi" chỉ bằng 1 request delete. Yêu cầu tất toán trước.
        if (supplier.getDebtBalance().signum() != 0) {
            throw new IllegalArgumentException(
                    "Cannot delete supplier with outstanding debt balance; settle the debt first");
        }

        supplier.setDeletedAt(Instant.now());
        supplierRepository.save(supplier);
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    // Scoped theo businessId để chống IDOR — supplier của business khác trả về 404
    Supplier findSupplierOrThrow(Long businessId, UUID publicId) {
        return supplierRepository.findByBusinessIdAndPublicId(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));
    }

    private SupplierResponse toResponse(Supplier s) {
        return new SupplierResponse(
                s.getId(), s.getPublicId(), s.getBusiness().getId(),
                s.getCode(), s.getName(), s.getPhone(), s.getEmail(), s.getAddress(),
                s.getDebtBalance(), s.getSyncVersion(), s.getLastModifiedAt(),
                s.getCreatedAt(), s.getUpdatedAt()
        );
    }
}
