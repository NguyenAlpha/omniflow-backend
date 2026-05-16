package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.inventory.InventoryAdjustRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.inventory.InventoryResponse;
import com.quiktech.backend.dto.response.inventory.InventoryTransactionResponse;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<InventoryResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return inventoryRepository.findByStoreIdAndDeletedAtIsNull(storeId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<InventoryResponse> listByWarehouse(Long storeId, UUID warehousePublicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Warehouse warehouse = warehouseRepository.findByPublicId(warehousePublicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));
        return inventoryRepository.findByWarehouseIdAndDeletedAtIsNull(warehouse.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionResponse> listTransactions(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return inventoryTransactionRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                .stream().map(this::toTxResponse).toList();
    }

    @Transactional
    public InventoryTransactionResponse adjust(Long storeId, InventoryAdjustRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        Product product = productRepository.findByPublicId(request.productPublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

        Warehouse warehouse = warehouseRepository.findByPublicId(request.warehousePublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));

        User userRef = userRepository.getReferenceById(currentUser.userId());

        Inventory inv = inventoryRepository
                .findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                .orElseGet(() -> Inventory.builder()
                        .product(product)
                        .warehouse(warehouse)
                        .store(store)
                        .quantity(BigDecimal.ZERO)
                        .publicId(UUID.randomUUID())
                        .lastModifiedByUser(userRef)
                        .build());

        inv.setQuantity(inv.getQuantity().add(request.quantity()));
        inv.setLastModifiedAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inv.setLastModifiedByUser(userRef);
        inventoryRepository.save(inv);

        InventoryTransaction tx = InventoryTransaction.builder()
                .store(store)
                .product(product)
                .warehouse(warehouse)
                .type("ADJUSTMENT")
                .quantity(request.quantity())
                .note(request.note())
                .createdBy(userRef)
                .build();
        inventoryTransactionRepository.save(tx);

        return toTxResponse(tx);
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private InventoryResponse toResponse(Inventory inv) {
        return new InventoryResponse(
                inv.getId(), inv.getPublicId(), inv.getStore().getId(),
                inv.getProduct().getPublicId(), inv.getProduct().getName(),
                inv.getWarehouse().getPublicId(), inv.getWarehouse().getName(),
                inv.getQuantity(), inv.getSyncVersion(), inv.getLastModifiedAt(),
                inv.getUpdatedAt()
        );
    }

    private InventoryTransactionResponse toTxResponse(InventoryTransaction tx) {
        return new InventoryTransactionResponse(
                tx.getId(), tx.getStore().getId(),
                tx.getProduct().getPublicId(), tx.getProduct().getName(),
                tx.getWarehouse().getPublicId(), tx.getWarehouse().getName(),
                tx.getType(), tx.getQuantity(),
                tx.getOrder() != null ? tx.getOrder().getPublicId() : null,
                tx.getPurchaseOrder() != null ? tx.getPurchaseOrder().getPublicId() : null,
                tx.getNote(), tx.getCreatedBy().getUsername(),
                tx.getCreatedAt()
        );
    }
}
