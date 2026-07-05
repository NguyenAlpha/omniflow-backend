package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.partner.CustomerPayRequest;
import com.quiktech.backend.dto.request.partner.CustomerUpsertRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.partner.CustomerResponse;
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
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;
    private final StoreRepository storeRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<CustomerResponse> list(Long businessId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return customerRepository.findByBusinessIdAndDeletedAtIsNull(businessId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResult<CustomerResponse> search(Long businessId, String q, Pageable pageable, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return PagedResult.of(customerRepository.searchCustomers(businessId, q, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return toResponse(findCustomerOrThrow(publicId));
    }

    @Transactional
    public CustomerResponse create(Long businessId, CustomerUpsertRequest request, UserPrincipal currentUser) {
        Business business = findBusinessOrThrow(businessId);

        if (customerRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, request.code()).isPresent()) {
            throw new IllegalArgumentException("Customer code already exists in this business");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Customer customer = Customer.builder()
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

        return toResponse(customerRepository.save(customer));
    }

    @Transactional
    public CustomerResponse update(Long businessId, UUID publicId, CustomerUpsertRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Customer customer = findCustomerOrThrow(publicId);

        customerRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, request.code())
                .filter(c -> !c.getPublicId().equals(publicId))
                .ifPresent(c -> { throw new IllegalArgumentException("Customer code already exists in this business"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());
        customer.setCode(request.code());
        customer.setName(request.name());
        customer.setPhone(request.phone());
        customer.setEmail(request.email());
        customer.setAddress(request.address());
        customer.setLastModifiedByUser(userRef);
        customer.setLastModifiedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());

        return toResponse(customerRepository.save(customer));
    }

    @Transactional
    public CustomerResponse pay(Long businessId, UUID publicId, CustomerPayRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Customer customer = findCustomerOrThrow(publicId);

        BigDecimal amount = request.amount();
        if (amount.compareTo(customer.getDebtBalance()) > 0) {
            throw new IllegalArgumentException("Payment amount exceeds customer debt balance");
        }

        customer.setDebtBalance(customer.getDebtBalance().subtract(amount));
        customer.setLastModifiedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());
        User userRef = userRepository.getReferenceById(currentUser.userId());
        customer.setLastModifiedByUser(userRef);
        Customer saved = customerRepository.save(customer);

        // Distribute payment across outstanding orders (oldest first)
        BigDecimal remaining = amount;
        for (Order order : orderRepository.findCompletedOrdersByCustomerWithDebt(saved.getId())) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal apply = remaining.min(order.getDebtAmount());
            order.setPaidAmount(order.getPaidAmount().add(apply));
            order.setDebtAmount(order.getDebtAmount().subtract(apply));
            order.setLastModifiedAt(Instant.now());
            order.setUpdatedAt(Instant.now());
            orderRepository.save(order);
            remaining = remaining.subtract(apply);
        }

        Store store = storeRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));

        String method = request.paymentMethod() != null ? request.paymentMethod() : "CASH";
        paymentRepository.save(Payment.builder()
                .store(store)
                .customer(saved)
                .amount(amount)
                .paymentMethod(method)
                .note("Customer payment: " + saved.getName())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build());

        return toResponse(saved);
    }

    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Customer customer = findCustomerOrThrow(publicId);
        customer.setDeletedAt(Instant.now());
        customerRepository.save(customer);
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    Customer findCustomerOrThrow(UUID publicId) {
        return customerRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found"));
    }

    private CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(
                c.getId(), c.getPublicId(), c.getBusiness().getId(),
                c.getCode(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(),
                c.getDebtBalance(), c.getSyncVersion(), c.getLastModifiedAt(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
