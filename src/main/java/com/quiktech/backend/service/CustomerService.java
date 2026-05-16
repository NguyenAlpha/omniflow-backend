package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.partner.CustomerUpsertRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.partner.CustomerResponse;
import com.quiktech.backend.entity.Customer;
import com.quiktech.backend.entity.Store;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.CustomerRepository;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<CustomerResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return customerRepository.findByStoreIdAndDeletedAtIsNull(storeId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResult<CustomerResponse> search(Long storeId, String q, Pageable pageable, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return PagedResult.of(customerRepository.searchCustomers(storeId, q, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return toResponse(findCustomerOrThrow(publicId));
    }

    @Transactional
    public CustomerResponse create(Long storeId, CustomerUpsertRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (customerRepository.findByStoreIdAndCodeAndDeletedAtIsNull(storeId, request.code()).isPresent()) {
            throw new IllegalArgumentException("Customer code already exists in this store");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Customer customer = Customer.builder()
                .store(store)
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
    public CustomerResponse update(Long storeId, UUID publicId, CustomerUpsertRequest request, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Customer customer = findCustomerOrThrow(publicId);

        customerRepository.findByStoreIdAndCodeAndDeletedAtIsNull(storeId, request.code())
                .filter(c -> !c.getPublicId().equals(publicId))
                .ifPresent(c -> { throw new IllegalArgumentException("Customer code already exists in this store"); });

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
    public void delete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Customer customer = findCustomerOrThrow(publicId);
        customer.setDeletedAt(Instant.now());
        customerRepository.save(customer);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    Customer findCustomerOrThrow(UUID publicId) {
        return customerRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found"));
    }

    private CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(
                c.getId(), c.getPublicId(), c.getStore().getId(),
                c.getCode(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(),
                c.getDebtBalance(), c.getSyncVersion(), c.getLastModifiedAt(),
                c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
