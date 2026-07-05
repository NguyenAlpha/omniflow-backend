package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.payment.PaymentCreateRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.payment.PaymentPageResult;
import com.quiktech.backend.dto.response.payment.PaymentResponse;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.entity.enums.PaymentMethod;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    public List<PaymentResponse> list(Long storeId) {
        findStoreOrThrow(storeId);
        return paymentRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long storeId, UUID publicId) {
        findStoreOrThrow(storeId);
        Payment payment = paymentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PAYMENT_NOT_FOUND, "Payment not found"));
        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentPageResult search(Long storeId, String direction, String method, String from, String to, Pageable pageable) {
        findStoreOrThrow(storeId);
        Instant fromInstant = (from != null && !from.isBlank())
                ? LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.EPOCH;
        Instant toInstant = (to != null && !to.isBlank())
                ? LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.parse("9999-12-31T00:00:00Z");
        String directionParam = (direction != null && !direction.isBlank()) ? direction.toUpperCase() : null;
        String methodParam = (method != null && !method.isBlank()) ? method : null;
        Page<Payment> page = paymentRepository.search(storeId, directionParam, methodParam, fromInstant, toInstant, pageable);
        BigDecimal income = paymentRepository.sumIncome(storeId, fromInstant, toInstant);
        BigDecimal expense = paymentRepository.sumExpense(storeId, fromInstant, toInstant);
        return new PaymentPageResult(
                page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                income, expense);
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
            if (request.paidAmount().compareTo(customer.getDebtBalance()) > 0) {
                throw new IllegalArgumentException("Payment amount exceeds customer debt balance");
            }
            // Customer pays → reduce debt
            customer.setDebtBalance(customer.getDebtBalance().subtract(request.paidAmount()));
            customerRepository.save(customer);
        } else {
            supplier = supplierRepository.findByPublicId(request.supplierPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));
            if (request.paidAmount().compareTo(supplier.getDebtBalance()) > 0) {
                throw new IllegalArgumentException("Payment amount exceeds supplier debt balance");
            }
            // We pay supplier → reduce our debt to supplier
            supplier.setDebtBalance(supplier.getDebtBalance().subtract(request.paidAmount()));
            supplierRepository.save(supplier);
        }

        Payment payment = Payment.builder()
                .store(store)
                .customer(customer)
                .supplier(supplier)
                .amount(request.paidAmount())
                .paymentMethod(String.valueOf(request.paymentMethod()))
                .note(request.note())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        return toResponse(paymentRepository.save(payment));
    }

    @Transactional
    public Payment createDebtPayment(Store store, BigDecimal amount, PaymentMethod paymentMethod, UUID customerPublicId, UUID supplierPublicId, UserPrincipal currentUser) {
        if(amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        Customer customer = null;
        Supplier supplier = null;
        User userRef = userRepository.getReferenceById(currentUser.userId());

        if (customerPublicId != null) {
            customer = customerRepository.findByPublicId(customerPublicId)
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found"));
            if (amount.compareTo(customer.getDebtBalance()) > 0) {
                throw new IllegalArgumentException("Payment amount exceeds customer debt balance");
            }
            // Customer pays → reduce debt
            customer.setDebtBalance(customer.getDebtBalance().subtract(amount));
            customerRepository.save(customer);
        }

        if (supplierPublicId != null) {
            supplier = supplierRepository.findByPublicId(supplierPublicId)
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));
            if (amount.compareTo(supplier.getDebtBalance()) > 0) {
                throw new IllegalArgumentException("Payment amount exceeds supplier debt balance");
            }
            // We pay supplier → reduce our debt to supplier
            supplier.setDebtBalance(supplier.getDebtBalance().subtract(amount));
            supplierRepository.save(supplier);
        }

        Payment payment = Payment.builder()
                .store(store)
                .customer(customer)
                .supplier(supplier)
                .amount(amount)
                .paymentMethod(paymentMethod != null ? paymentMethod.name() : PaymentMethod.CASH.name())
                .note("Payment for order")
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment createDirectPayment(Store store, BigDecimal amount, PaymentMethod paymentMethod, UUID customerPublicId, UUID supplierPublicId, UserPrincipal currentUser) {
        if(amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        Customer customer = null;
        Supplier supplier = null;
        User userRef = userRepository.getReferenceById(currentUser.userId());

        if (customerPublicId != null) {
            customer = customerRepository.findByPublicId(customerPublicId)
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found"));
        }

        if (supplierPublicId != null) {
            supplier = supplierRepository.findByPublicId(supplierPublicId)
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));
        }

        Payment payment = Payment.builder()
                .store(store)
                .customer(customer)
                .supplier(supplier)
                .amount(amount)
                .paymentMethod(paymentMethod != null ? paymentMethod.name() : PaymentMethod.CASH.name())
                .note("Payment for order")
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        return paymentRepository.save(payment);
    }


    @Transactional
    public void delete(Long storeId, UUID publicId) {
        findStoreOrThrow(storeId);
        Payment payment = paymentRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PAYMENT_NOT_FOUND, "Payment not found"));
        // Reverse the debt adjustment made when payment was created
        if (payment.getCustomer() != null) {
            Customer customer = payment.getCustomer();
            customer.setDebtBalance(customer.getDebtBalance().add(payment.getAmount()));
            customerRepository.save(customer);
        } else if (payment.getSupplier() != null) {
            Supplier supplier = payment.getSupplier();
            supplier.setDebtBalance(supplier.getDebtBalance().add(payment.getAmount()));
            supplierRepository.save(supplier);
        }
        paymentRepository.delete(payment);
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
