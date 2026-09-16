package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.warehouse.WarehouseUpsertRequest;
import com.quiktech.pos.dto.response.warehouse.WarehouseResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.entity.Store;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.Warehouse;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.InventoryRepository;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.repository.WarehouseRepository;
import com.quiktech.pos.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final InventoryRepository inventoryRepository;
    private final SubscriptionLimitService subscriptionLimitService;

    @Transactional(readOnly = true)
    public List<WarehouseResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return warehouseRepository.findByStoreIdAndDeletedAtIsNull(storeId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return toResponse(findWarehouseOrThrow(storeId, publicId));
    }

    @Transactional
    public WarehouseResponse create(Long storeId, WarehouseUpsertRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        subscriptionLimitService.checkWarehouseLimit(store.getBusiness().getId());

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
        Warehouse warehouse = findWarehouseOrThrow(storeId, publicId);

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
        Warehouse warehouse = findWarehouseOrThrow(storeId, publicId);
        // Xóa kho còn hàng để lại tồn kho "ma" (vẫn cộng vào total_stock nhưng không bán được)
        if (inventoryRepository.sumQuantityByWarehouseId(warehouse.getId()).compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException("Cannot delete a warehouse that still has stock. Transfer stock out first");
        }
        warehouse.setDeletedAt(Instant.now());
        warehouseRepository.save(warehouse);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    // Lookup scoped theo store để chống IDOR (kho của tenant khác → 404)
    private Warehouse findWarehouseOrThrow(Long storeId, UUID publicId) {
        return warehouseRepository.findByPublicIdAndStoreId(publicId, storeId)
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
