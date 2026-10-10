package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.inventory.InventoryAdjustRequest;
import com.quiktech.pos.dto.request.inventory.InventoryBulkAdjustRequest;
import com.quiktech.pos.dto.request.inventory.InventoryBulkTransferRequest;
import com.quiktech.pos.dto.request.inventory.InventoryTransferRequest;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.inventory.InventoryResponse;
import com.quiktech.pos.dto.response.inventory.InventoryTransactionResponse;
import com.quiktech.pos.entity.*;
import com.quiktech.pos.entity.enums.InventoryTransactionType;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.annotation.Auditable;
import com.quiktech.pos.repository.*;
import com.quiktech.pos.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        // Lookup scoped theo store để chống IDOR (kho của tenant khác → 404)
        Warehouse warehouse = warehouseRepository.findByPublicIdAndStoreId(warehousePublicId, storeId)
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

    @Auditable(action = "ADJUST_INVENTORY", entityType = "INVENTORY")
    @Transactional
    public InventoryTransactionResponse adjust(Long storeId, InventoryAdjustRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        // Product thuộc scope business, warehouse thuộc scope store → lookup scoped để chống IDOR
        Product product = productRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), request.productPublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

        Warehouse warehouse = warehouseRepository.findByPublicIdAndStoreId(request.warehousePublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));

        User userRef = userRepository.getReferenceById(currentUser.userId());

        return toTxResponse(applyAdjustment(store, product, warehouse, request.quantity(), request.note(), userRef));
    }

    /**
     * Điều chỉnh nhiều product trong một kho, all-or-nothing: một dòng lỗi (product không
     * thuộc business, tồn kho âm) → exception rollback cả lô, không có dòng nào được ghi.
     */
    @Auditable(action = "BULK_ADJUST_INVENTORY", entityType = "INVENTORY")
    @Transactional
    public List<InventoryTransactionResponse> bulkAdjust(Long storeId, InventoryBulkAdjustRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        Warehouse warehouse = warehouseRepository.findByPublicIdAndStoreId(request.warehousePublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));

        // Validate cả lô trước khi ghi. Trùng product → dòng sau tính trên previousQuantity
        // đã bị dòng trước đổi, khó hiểu cho user
        Set<UUID> seen = new HashSet<>();
        for (InventoryBulkAdjustRequest.Item item : request.items()) {
            if (!seen.add(item.productPublicId())) {
                throw new IllegalArgumentException("Duplicate product in adjustment: " + item.productPublicId());
            }
            // DB có CHECK quantity <> 0 — chặn ở đây để trả 400 thay vì lỗi constraint 500
            if (item.quantity().signum() == 0) {
                throw new IllegalArgumentException("Adjustment quantity must not be zero: " + item.productPublicId());
            }
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        List<InventoryTransactionResponse> result = new ArrayList<>();
        for (InventoryBulkAdjustRequest.Item item : request.items()) {
            // Product thuộc scope business → lookup scoped để chống IDOR
            Product product = productRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), item.productPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + item.productPublicId()));
            try {
                result.add(toTxResponse(applyAdjustment(store, product, warehouse, item.quantity(), request.note(), userRef)));
            } catch (IllegalArgumentException e) {
                // Gắn tên product để UI chỉ ra được dòng nào sai
                throw new IllegalArgumentException(product.getName() + " (" + product.getSku() + "): " + e.getMessage());
            }
        }
        return result;
    }

    // Logic điều chỉnh 1 dòng dùng chung cho adjust và bulkAdjust — caller đã lookup scoped product/warehouse
    private InventoryTransaction applyAdjustment(Store store, Product product, Warehouse warehouse,
                                                 BigDecimal quantity, String note, User userRef) {
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

        BigDecimal previousQuantity = inv.getQuantity();
        BigDecimal newQuantity = previousQuantity.add(quantity);
        // Delta âm không được đẩy tồn kho xuống dưới 0 — kho âm phá invariant của deduct/transfer
        if (newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Adjustment would result in negative stock");
        }
        inv.setQuantity(newQuantity);
        inv.setLastModifiedAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inv.setLastModifiedByUser(userRef);
        inventoryRepository.save(inv);
        productRepository.recalculateTotalStock(product.getId());

        InventoryTransaction tx = InventoryTransaction.builder()
                .store(store)
                .product(product)
                .warehouse(warehouse)
                .type(InventoryTransactionType.ADJUSTMENT)
                .quantity(quantity)
                .previousQuantity(previousQuantity)
                .note(note)
                .createdBy(userRef)
                .build();
        inventoryTransactionRepository.save(tx);

        return tx;
    }

    @Auditable(action = "TRANSFER_INVENTORY", entityType = "INVENTORY")
    @Transactional
    public List<InventoryTransactionResponse> transfer(Long storeId, InventoryTransferRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (request.fromWarehousePublicId().equals(request.toWarehousePublicId())) {
            throw new IllegalArgumentException("Source and destination warehouse must be different");
        }

        // Product thuộc scope business, warehouse thuộc scope store → lookup scoped để chống IDOR
        Product product = productRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), request.productPublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

        Warehouse fromWarehouse = warehouseRepository.findByPublicIdAndStoreId(request.fromWarehousePublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Source warehouse not found"));

        Warehouse toWarehouse = warehouseRepository.findByPublicIdAndStoreId(request.toWarehousePublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Destination warehouse not found"));
        // Không chuyển hàng vào kho inactive; chuyển RA vẫn cho phép để rút hàng trước khi xóa kho
        if (!toWarehouse.getIsActive()) {
            throw new IllegalArgumentException("Destination warehouse is inactive");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        return applyTransfer(store, product, fromWarehouse, toWarehouse, request.quantity(), request.note(), userRef)
                .stream().map(this::toTxResponse).toList();
    }

    /**
     * Chuyển nhiều product từ một kho sang một kho khác, all-or-nothing: một dòng lỗi (product
     * không thuộc business, không đủ tồn kho nguồn) → exception rollback cả lô.
     */
    @Auditable(action = "BULK_TRANSFER_INVENTORY", entityType = "INVENTORY")
    @Transactional
    public List<InventoryTransactionResponse> bulkTransfer(Long storeId, InventoryBulkTransferRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (request.fromWarehousePublicId().equals(request.toWarehousePublicId())) {
            throw new IllegalArgumentException("Source and destination warehouse must be different");
        }

        Warehouse fromWarehouse = warehouseRepository.findByPublicIdAndStoreId(request.fromWarehousePublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Source warehouse not found"));

        Warehouse toWarehouse = warehouseRepository.findByPublicIdAndStoreId(request.toWarehousePublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Destination warehouse not found"));
        // Không chuyển hàng vào kho inactive; chuyển RA vẫn cho phép để rút hàng trước khi xóa kho
        if (!toWarehouse.getIsActive()) {
            throw new IllegalArgumentException("Destination warehouse is inactive");
        }

        Set<UUID> seen = new HashSet<>();
        for (InventoryBulkTransferRequest.Item item : request.items()) {
            if (!seen.add(item.productPublicId())) {
                throw new IllegalArgumentException("Duplicate product in transfer: " + item.productPublicId());
            }
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        List<InventoryTransactionResponse> result = new ArrayList<>();
        for (InventoryBulkTransferRequest.Item item : request.items()) {
            // Product thuộc scope business → lookup scoped để chống IDOR
            Product product = productRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), item.productPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + item.productPublicId()));
            try {
                applyTransfer(store, product, fromWarehouse, toWarehouse, item.quantity(), request.note(), userRef)
                        .forEach(tx -> result.add(toTxResponse(tx)));
            } catch (IllegalStateException e) {
                // Gắn tên product để chỉ ra được dòng nào không đủ tồn kho
                throw new IllegalStateException(product.getName() + " (" + product.getSku() + "): " + e.getMessage());
            }
        }
        return result;
    }

    // Logic chuyển 1 product dùng chung cho transfer và bulkTransfer — caller đã lookup scoped
    // product/warehouse và kiểm tra kho đích active. Trả về [chân xuất, chân nhập].
    private List<InventoryTransaction> applyTransfer(Store store, Product product, Warehouse fromWarehouse, Warehouse toWarehouse,
                                                     BigDecimal quantity, String note, User userRef) {
        Inventory fromInv = inventoryRepository.findByProductIdAndWarehouseId(product.getId(), fromWarehouse.getId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.INVENTORY_NOT_FOUND, "No inventory found in source warehouse"));

        if (fromInv.getQuantity().compareTo(quantity) < 0) {
            throw new IllegalStateException("Insufficient stock in source warehouse");
        }

        BigDecimal fromPrev = fromInv.getQuantity();
        fromInv.setQuantity(fromPrev.subtract(quantity));
        fromInv.setLastModifiedAt(Instant.now());
        fromInv.setUpdatedAt(Instant.now());
        fromInv.setLastModifiedByUser(userRef);
        inventoryRepository.save(fromInv);

        Inventory toInv = inventoryRepository
                .findByProductIdAndWarehouseId(product.getId(), toWarehouse.getId())
                .orElseGet(() -> Inventory.builder()
                        .product(product)
                        .warehouse(toWarehouse)
                        .store(store)
                        .quantity(BigDecimal.ZERO)
                        .publicId(UUID.randomUUID())
                        .lastModifiedByUser(userRef)
                        .build());

        BigDecimal toPrev = toInv.getQuantity();
        toInv.setQuantity(toPrev.add(quantity));
        toInv.setLastModifiedAt(Instant.now());
        toInv.setUpdatedAt(Instant.now());
        toInv.setLastModifiedByUser(userRef);
        inventoryRepository.save(toInv);

        productRepository.recalculateTotalStock(product.getId());

        InventoryTransaction outTx = InventoryTransaction.builder()
                .store(store).product(product).warehouse(fromWarehouse)
                .type(InventoryTransactionType.TRANSFER).quantity(quantity.negate()).previousQuantity(fromPrev)
                .note(note).createdBy(userRef).build();

        InventoryTransaction inTx = InventoryTransaction.builder()
                .store(store).product(product).warehouse(toWarehouse)
                .type(InventoryTransactionType.TRANSFER).quantity(quantity).previousQuantity(toPrev)
                .note(note).createdBy(userRef).build();

        inventoryTransactionRepository.save(outTx);
        inventoryTransactionRepository.save(inTx);

        return List.of(outTx, inTx);
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
                tx.getType().name(), tx.getQuantity(), tx.getPreviousQuantity(),
                tx.getOrder() != null ? tx.getOrder().getPublicId() : null,
                tx.getPurchaseOrder() != null ? tx.getPurchaseOrder().getPublicId() : null,
                tx.getNote(), tx.getCreatedBy().getUsername(),
                tx.getCreatedAt()
        );
    }
}
