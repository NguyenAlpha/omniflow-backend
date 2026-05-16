package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.payment.PaymentCreateRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.payment.PaymentResponse;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return paymentRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Payment payment = paymentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PAYMENT_NOT_FOUND, "Payment not found"));
        return toResponse(payment);
    }

    @Transactional
    public PaymentResponse create(Long storeId, PaymentCreateRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        boolean hasCustomer = request.customerPublicId() != null;
        boolean hasSupplier = request.supplierPublicId() != null;

        if (hasCustomer == hasSupplier) {
            throw new IllegalArgumentException("Payment must be linked to exactly one of customer or supplier");
        }

        Customer customer = null;
        Supplier supplier = null;
        User userRef = userRepository.getReferenceById(currentUser.userId());

        if (hasCustomer) {
            customer = customerRepository.findByPublicId(request.customerPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found"));
            // Customer pays → reduce debt
            customer.setDebtBalance(customer.getDebtBalance().subtract(request.amount()).max(BigDecimal.ZERO));
            customerRepository.save(customer);
        } else {
            supplier = supplierRepository.findByPublicId(request.supplierPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));
            // We pay supplier → reduce our debt to supplier
            supplier.setDebtBalance(supplier.getDebtBalance().subtract(request.amount()).max(BigDecimal.ZERO));
            supplierRepository.save(supplier);
        }

        Payment payment = Payment.builder()
                .store(store)
                .customer(customer)
                .supplier(supplier)
                .amount(request.amount())
                .paymentMethod(request.paymentMethod())
                .note(request.note())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        return toResponse(paymentRepository.save(payment));
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(), p.getPublicId(), p.getStore().getId(),
                p.getCustomer() != null ? p.getCustomer().getPublicId() : null,
                p.getSupplier() != null ? p.getSupplier().getPublicId() : null,
                p.getAmount(), p.getPaymentMethod(), p.getNote(),
                p.getSyncVersion(), p.getLastModifiedAt(),
                p.getCreatedAt()
        );
    }
}
