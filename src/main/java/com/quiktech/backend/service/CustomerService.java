package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.partner.CustomerPayRequest;
import com.quiktech.backend.dto.request.partner.CustomerUpsertRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.partner.CustomerResponse;
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
        return PagedResult.of(customerRepository.searchCustomers(businessId, escapeLike(q), pageable).map(this::toResponse));
    }

    // Escape ký tự wildcard của LIKE/ILIKE để từ khóa được hiểu là chuỗi thường —
    // không escape thì q = "%" match toàn bộ bảng. Khớp với ESCAPE '\' trong searchCustomers.
    private static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        return toResponse(findCustomerOrThrow(businessId, publicId));
    }

    @Transactional
    public CustomerResponse create(Long businessId, CustomerUpsertRequest request, UserPrincipal currentUser) {
        Business business = findBusinessOrThrow(businessId);

        // Trim trước khi check unique + lưu: không trim thì " KH01" và "KH01" cùng tồn tại được
        String code = request.code().trim();
        String name = request.name().trim();

        if (customerRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, code).isPresent()) {
            throw new IllegalArgumentException("Customer code already exists in this business");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Customer customer = Customer.builder()
                .business(business)
                .code(code)
                .name(name)
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
        Customer customer = findCustomerOrThrow(businessId, publicId);

        // Trim trước khi check unique + lưu (xem chú thích ở create)
        String code = request.code().trim();
        String name = request.name().trim();

        customerRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(businessId, code)
                .filter(c -> !c.getPublicId().equals(publicId))
                .ifPresent(c -> { throw new IllegalArgumentException("Customer code already exists in this business"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());
        customer.setCode(code);
        customer.setName(name);
        customer.setPhone(request.phone());
        customer.setEmail(request.email());
        customer.setAddress(request.address());
        customer.setLastModifiedByUser(userRef);
        customer.setLastModifiedAt(Instant.now());
        customer.setUpdatedAt(Instant.now());

        return toResponse(customerRepository.save(customer));
    }

    @Auditable(action = "PAY_CUSTOMER_DEBT", entityType = "CUSTOMER")
    @Transactional
    public CustomerResponse pay(Long businessId, UUID publicId, CustomerPayRequest request, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        // PESSIMISTIC_WRITE: serialize các request pay đồng thời trên cùng customer,
        // tránh 2 giao dịch cùng đọc một debtBalance rồi cùng trừ → double-payment
        Customer customer = customerRepository.findByBusinessIdAndPublicIdForUpdate(businessId, publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found"));

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

        // remaining > 0 nghĩa là debtBalance và tổng debtAmount của các order đã lệch
        // từ trước — không nuốt im lặng, log warn để phát hiện lệch sổ tích lũy
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("Customer debt payment: {} remaining after allocating across completed orders "
                    + "(customerId={}, businessId={}) — debtBalance lệch với tổng debtAmount của order",
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
            log.warn("Customer debt payment without storeId: assigned to first store {} of business {}",
                    store.getId(), businessId);
        }

        PaymentMethod method = request.paymentMethod() != null ? request.paymentMethod() : PaymentMethod.CASH;
        paymentRepository.save(Payment.builder()
                .store(store)
                .customer(saved)
                .amount(amount)
                .paymentMethod(method.name())
                .note("Customer payment: " + saved.getName())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build());

        return toResponse(saved);
    }

    @Auditable(action = "DELETE_CUSTOMER", entityType = "CUSTOMER")
    @Transactional
    public void delete(Long businessId, UUID publicId, UserPrincipal currentUser) {
        findBusinessOrThrow(businessId);
        Customer customer = findCustomerOrThrow(businessId, publicId);

        // Chặn xóa khi còn công nợ: sau soft-delete, khách biến mất khỏi
        // findCustomersWithDebt và mọi báo cáo công nợ (filter deleted_at IS NULL)
        // → khoản nợ "bốc hơi" chỉ bằng 1 request delete. Yêu cầu tất toán trước.
        if (customer.getDebtBalance().signum() != 0) {
            throw new IllegalArgumentException(
                    "Cannot delete customer with outstanding debt balance; settle the debt first");
        }

        customer.setDeletedAt(Instant.now());
        // Soft delete cũng là mutation — set trường sync để client local-first nhận được
        // tín hiệu "record đã xóa" khi sync delta được implement
        customer.setLastModifiedByUser(userRepository.getReferenceById(currentUser.userId()));
        customer.setLastModifiedAt(Instant.now());
        customerRepository.save(customer);
    }

    private Business findBusinessOrThrow(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.BUSINESS_NOT_FOUND, "Business not found"));
    }

    // Scoped theo businessId để chống IDOR — customer của business khác trả về 404
    Customer findCustomerOrThrow(Long businessId, UUID publicId) {
        return customerRepository.findByBusinessIdAndPublicId(businessId, publicId)
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
