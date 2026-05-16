package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.warehouse.WarehouseUpsertRequest;
import com.quiktech.backend.dto.response.warehouse.WarehouseResponse;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.entity.Store;
import com.quiktech.backend.entity.User;
import com.quiktech.backend.entity.Warehouse;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.StoreRepository;
import com.quiktech.backend.repository.UserRepository;
import com.quiktech.backend.repository.WarehouseRepository;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<WarehouseResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return warehouseRepository.findByStoreIdAndDeletedAtIsNull(storeId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return toResponse(findWarehouseOrThrow(publicId));
    }

    @Transactional
    public WarehouseResponse create(Long storeId, WarehouseUpsertRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (warehouseRepository.findByStoreIdAndNameAndDeletedAtIsNull(storeId, request.name()).isPresent()) {
            throw new IllegalArgumentException("Warehouse name already exists in this store");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Warehouse warehouse = Warehouse.builder()
                .store(store)
                .name(request.name())
                .address(request.address())
                .isActive(request.isActive() != null ? request.isActive() : Boolean.TRUE)
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .build();

        return toResponse(warehouseRepository.save(warehouse));
    }

    @Transactional
    public WarehouseResponse update(Long storeId, UUID publicId, WarehouseUpsertRequest request, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Warehouse warehouse = findWarehouseOrThrow(publicId);

        warehouseRepository.findByStoreIdAndNameAndDeletedAtIsNull(storeId, request.name())
                .filter(w -> !w.getPublicId().equals(publicId))
                .ifPresent(w -> { throw new IllegalArgumentException("Warehouse name already exists in this store"); });

        User userRef = userRepository.getReferenceById(currentUser.userId());
        warehouse.setName(request.name());
        warehouse.setAddress(request.address());
        if (request.isActive() != null) warehouse.setIsActive(request.isActive());
        warehouse.setLastModifiedByUser(userRef);
        warehouse.setLastModifiedAt(Instant.now());
        warehouse.setUpdatedAt(Instant.now());

        return toResponse(warehouseRepository.save(warehouse));
    }

    @Transactional
    public void delete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Warehouse warehouse = findWarehouseOrThrow(publicId);
        warehouse.setDeletedAt(Instant.now());
        warehouseRepository.save(warehouse);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private Warehouse findWarehouseOrThrow(UUID publicId) {
        return warehouseRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));
    }

    private WarehouseResponse toResponse(Warehouse w) {
        return new WarehouseResponse(
                w.getId(), w.getPublicId(), w.getStore().getId(),
                w.getName(), w.getAddress(), w.getIsActive(),
                w.getSyncVersion(), w.getLastModifiedAt(),
                w.getCreatedAt(), w.getUpdatedAt()
        );
    }
}
