package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.partner.SupplierUpsertRequest;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.dto.response.common.PagedResult;
import com.omniflow.backend.dto.response.partner.SupplierResponse;
import com.omniflow.backend.entity.Store;
import com.omniflow.backend.entity.Supplier;
import com.omniflow.backend.entity.User;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.StoreRepository;
import com.omniflow.backend.repository.SupplierRepository;
import com.omniflow.backend.repository.UserRepository;
import com.omniflow.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SupplierResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return supplierRepository.findByStoreIdAndDeletedAtIsNull(storeId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PagedResult<SupplierResponse> search(Long storeId, String q, Pageable pageable, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return PagedResult.of(supplierRepository.searchSuppliers(storeId, q, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return toResponse(findSupplierOrThrow(publicId));
    }

    @Transactional
    public SupplierResponse create(Long storeId, SupplierUpsertRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (supplierRepository.findByStoreIdAndCodeAndDeletedAtIsNull(storeId, request.code()).isPresent()) {
            throw new IllegalArgumentException("Supplier code already exists in this store");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Supplier supplier = Supplier.builder()
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

        return toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public SupplierResponse update(Long storeId, UUID publicId, SupplierUpsertRequest request, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Supplier supplier = findSupplierOrThrow(publicId);

        supplierRepository.findByStoreIdAndCodeAndDeletedAtIsNull(storeId, request.code())
                .filter(s -> !s.getPublicId().equals(publicId))
                .ifPresent(s -> { throw new IllegalArgumentException("Supplier code already exists in this store"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());
        supplier.setCode(request.code());
        supplier.setName(request.name());
        supplier.setPhone(request.phone());
        supplier.setEmail(request.email());
        supplier.setAddress(request.address());
        supplier.setLastModifiedByUser(userRef);
        supplier.setLastModifiedAt(LocalDateTime.now());
        supplier.setUpdatedAt(LocalDateTime.now());

        return toResponse(supplierRepository.save(supplier));
    }

    @Transactional
    public void delete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Supplier supplier = findSupplierOrThrow(publicId);
        supplier.setDeletedAt(LocalDateTime.now());
        supplierRepository.save(supplier);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    Supplier findSupplierOrThrow(UUID publicId) {
        return supplierRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));
    }

    private SupplierResponse toResponse(Supplier s) {
        return new SupplierResponse(
                s.getId(), s.getPublicId(), s.getStore().getId(),
                s.getCode(), s.getName(), s.getPhone(), s.getEmail(), s.getAddress(),
                s.getDebtBalance(), s.getSyncVersion(), s.getLastModifiedAt(),
                s.getCreatedAt(), s.getUpdatedAt()
        );
    }
}
