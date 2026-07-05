package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.partner.SupplierPayRequest;
import com.quiktech.backend.dto.request.partner.SupplierUpsertRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.partner.SupplierResponse;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
        return toResponse(findSupplierOrThrow(publicId));
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
        Supplier supplier = findSupplierOrThrow(publicId);

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

    @Transactional
    public SupplierResponse pay(Long businessId, UUID publicId, SupplierPayRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Supplier supplier = findSupplierOrThrow(publicId);

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

        Store store = storeRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));

        String method = request.paymentMethod() != null ? request.paymentMethod() : "CASH";
        paymentRepository.save(Payment.builder()
                .store(store)
                .supplier(saved)
                .amount(amount)
                .paymentMethod(method)
                .note("Supplier payment: " + saved.getName())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build());

        return toResponse(saved);
    }

    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Supplier supplier = findSupplierOrThrow(publicId);
        supplier.setDeletedAt(Instant.now());
        supplierRepository.save(supplier);
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    Supplier findSupplierOrThrow(UUID publicId) {
        return supplierRepository.findByPublicId(publicId)
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
