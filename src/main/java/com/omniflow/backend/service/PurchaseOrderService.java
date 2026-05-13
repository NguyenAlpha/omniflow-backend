package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.purchase.PurchaseOrderCreateRequest;
import com.omniflow.backend.dto.request.purchase.PurchaseOrderItemRequest;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.dto.response.purchase.PurchaseOrderItemResponse;
import com.omniflow.backend.dto.response.purchase.PurchaseOrderResponse;
import com.omniflow.backend.entity.*;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.*;
import com.omniflow.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StoreRepository storeRepository;
    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<PurchaseOrderResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return purchaseOrderRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                .stream().map(po -> toResponse(po, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        PurchaseOrder po = purchaseOrderRepository.findByPublicIdWithItems(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "Purchase order not found"));
        return toResponse(po, po.getPurchaseOrderItems());
    }

    @Transactional
    public PurchaseOrderResponse create(Long storeId, PurchaseOrderCreateRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (purchaseOrderRepository.findByStoreIdAndOrderCode(storeId, request.orderCode()).isPresent()) {
            throw new IllegalArgumentException("Order code already exists in this store");
        }

        Supplier supplier = supplierRepository.findByPublicId(request.supplierPublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));

        Warehouse warehouse = warehouseRepository.findByPublicId(request.warehousePublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));

        User userRef = userRepository.getReferenceById(currentUser.userId());

        PurchaseOrder po = PurchaseOrder.builder()
                .store(store)
                .orderCode(request.orderCode())
                .supplier(supplier)
                .warehouse(warehouse)
                .status("PENDING")
                .totalAmount(BigDecimal.ZERO)
                .paidAmount(BigDecimal.ZERO)
                .debtAmount(BigDecimal.ZERO)
                .note(request.note())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        List<PurchaseOrderItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (PurchaseOrderItemRequest itemReq : request.items()) {
            Product product = productRepository.findByPublicId(itemReq.productPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

            BigDecimal lineTotal = itemReq.unitPrice().multiply(itemReq.quantity());

            PurchaseOrderItem item = PurchaseOrderItem.builder()
                    .purchaseOrder(po)
                    .product(product)
                    .store(store)
                    .quantity(itemReq.quantity())
                    .unitPrice(itemReq.unitPrice())
                    .totalPrice(lineTotal)
                    .build();

            items.add(item);
            totalAmount = totalAmount.add(lineTotal);
        }

        po.setTotalAmount(totalAmount);
        po.setDebtAmount(totalAmount);
        po.setPurchaseOrderItems(items);
        PurchaseOrder saved = purchaseOrderRepository.save(po);

        return toResponse(saved, items);
    }

    @Transactional
    public PurchaseOrderResponse receive(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        PurchaseOrder po = purchaseOrderRepository.findByPublicIdWithItems(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "Purchase order not found"));

        if (!"PENDING".equals(po.getStatus())) {
            throw new IllegalArgumentException("Purchase order is not in PENDING status");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Add goods to inventory
        for (PurchaseOrderItem item : po.getPurchaseOrderItems()) {
            addToInventory(po.getStore(), item.getProduct(), po.getWarehouse(),
                    item.getQuantity(), po, userRef);
        }

        // Add debt to supplier balance
        if (po.getDebtAmount().compareTo(BigDecimal.ZERO) > 0) {
            Supplier supplier = po.getSupplier();
            supplier.setDebtBalance(supplier.getDebtBalance().add(po.getDebtAmount()));
            supplierRepository.save(supplier);
        }

        po.setStatus("RECEIVED");
        po.setLastModifiedByUser(userRef);
        po.setLastModifiedAt(Instant.now());
        po.setUpdatedAt(Instant.now());

        return toResponse(purchaseOrderRepository.save(po), po.getPurchaseOrderItems());
    }

    @Transactional
    public PurchaseOrderResponse cancel(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        PurchaseOrder po = purchaseOrderRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "Purchase order not found"));

        if (!"PENDING".equals(po.getStatus())) {
            throw new IllegalArgumentException("Purchase order is not in PENDING status");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());
        po.setStatus("CANCELLED");
        po.setLastModifiedByUser(userRef);
        po.setLastModifiedAt(Instant.now());
        po.setUpdatedAt(Instant.now());

        return toResponse(purchaseOrderRepository.save(po), List.of());
    }

    private void addToInventory(Store store, Product product, Warehouse warehouse,
            BigDecimal quantity, PurchaseOrder po, User userRef) {
        Inventory inv = inventoryRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                .orElseGet(() -> Inventory.builder()
                        .product(product).warehouse(warehouse).store(store)
                        .quantity(BigDecimal.ZERO).publicId(UUID.randomUUID())
                        .lastModifiedByUser(userRef).build());

        inv.setQuantity(inv.getQuantity().add(quantity));
        inv.setLastModifiedAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inv.setLastModifiedByUser(userRef);
        inventoryRepository.save(inv);

        inventoryTransactionRepository.save(InventoryTransaction.builder()
                .store(store).product(product).warehouse(warehouse)
                .type("IN").quantity(quantity).purchaseOrder(po)
                .note("Receive PO: " + po.getOrderCode()).createdBy(userRef)
                .build());
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder po, List<PurchaseOrderItem> items) {
        return new PurchaseOrderResponse(
                po.getId(), po.getPublicId(), po.getStore().getId(),
                po.getOrderCode(), po.getSupplier().getPublicId(),
                po.getWarehouse().getPublicId(),
                po.getStatus(), po.getTotalAmount(), po.getPaidAmount(), po.getDebtAmount(),
                po.getNote(), po.getSyncVersion(), po.getLastModifiedAt(),
                po.getCreatedAt(), po.getUpdatedAt(),
                items.stream().map(this::toItemResponse).toList()
        );
    }

    private PurchaseOrderItemResponse toItemResponse(PurchaseOrderItem i) {
        return new PurchaseOrderItemResponse(
                i.getId(), i.getProduct().getPublicId(), i.getProduct().getName(),
                i.getQuantity(), i.getUnitPrice(), i.getTotalPrice()
        );
    }
}
