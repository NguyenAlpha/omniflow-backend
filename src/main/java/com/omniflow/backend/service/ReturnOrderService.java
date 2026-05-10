package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.order.ReturnOrderCreateRequest;
import com.omniflow.backend.dto.request.order.ReturnOrderItemRequest;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.dto.response.order.ReturnOrderItemResponse;
import com.omniflow.backend.dto.response.order.ReturnOrderResponse;
import com.omniflow.backend.entity.*;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.*;
import com.omniflow.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReturnOrderService {

    private final ReturnOrderRepository returnOrderRepository;
    private final OrderRepository orderRepository;
    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<ReturnOrderResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return returnOrderRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                .stream().map(r -> toResponse(r, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public ReturnOrderResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        ReturnOrder returnOrder = returnOrderRepository.findByPublicIdWithItems(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RETURN_ORDER_NOT_FOUND, "Return order not found"));
        return toResponse(returnOrder, returnOrder.getReturnOrderItems());
    }

    @Transactional
    public ReturnOrderResponse create(Long storeId, ReturnOrderCreateRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (returnOrderRepository.findByStoreIdAndReturnCode(storeId, request.returnCode()).isPresent()) {
            throw new IllegalArgumentException("Return code already exists in this store");
        }

        Order originalOrder = orderRepository.findByPublicId(request.originalOrderPublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Original order not found"));

        Warehouse warehouse = warehouseRepository.findByPublicId(request.warehousePublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));

        User userRef = userRepository.getReferenceById(currentUser.userId());

        ReturnOrder returnOrder = ReturnOrder.builder()
                .store(store)
                .returnCode(request.returnCode())
                .originalOrder(originalOrder)
                .warehouse(warehouse)
                .status("PENDING")
                .reason(request.reason())
                .totalRefund(BigDecimal.ZERO)
                .refundMethod(request.refundMethod())
                .note(request.note())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        List<ReturnOrderItem> items = new ArrayList<>();
        BigDecimal totalRefund = BigDecimal.ZERO;

        for (ReturnOrderItemRequest itemReq : request.items()) {
            Product product = productRepository.findByPublicId(itemReq.productPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

            BigDecimal itemRefund = itemReq.unitPrice().multiply(itemReq.quantity());

            ReturnOrderItem item = ReturnOrderItem.builder()
                    .store(store)
                    .returnOrder(returnOrder)
                    .product(product)
                    .quantity(itemReq.quantity())
                    .unitPrice(itemReq.unitPrice())
                    .totalRefund(itemRefund)
                    .build();

            items.add(item);
            totalRefund = totalRefund.add(itemRefund);
        }

        returnOrder.setTotalRefund(totalRefund);
        returnOrder.setReturnOrderItems(items);
        ReturnOrder saved = returnOrderRepository.save(returnOrder);

        return toResponse(saved, items);
    }

    @Transactional
    public ReturnOrderResponse complete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        ReturnOrder returnOrder = returnOrderRepository.findByPublicIdWithItems(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RETURN_ORDER_NOT_FOUND, "Return order not found"));

        if (!"PENDING".equals(returnOrder.getStatus())) {
            throw new IllegalArgumentException("Return order is not in PENDING status");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Restore inventory for each returned item
        for (ReturnOrderItem item : returnOrder.getReturnOrderItems()) {
            restoreInventory(returnOrder.getStore(), item.getProduct(), returnOrder.getWarehouse(),
                    item.getQuantity(), userRef, returnOrder.getReturnCode());
        }

        // Reduce customer debt if applicable
        Customer customer = returnOrder.getOriginalOrder().getCustomer();
        if (customer != null && returnOrder.getTotalRefund().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal newDebt = customer.getDebtBalance().subtract(returnOrder.getTotalRefund());
            customer.setDebtBalance(newDebt.max(BigDecimal.ZERO));
            customerRepository.save(customer);
        }

        returnOrder.setStatus("COMPLETED");
        returnOrder.setLastModifiedByUser(userRef);
        returnOrder.setLastModifiedAt(LocalDateTime.now());
        returnOrder.setUpdatedAt(LocalDateTime.now());

        return toResponse(returnOrderRepository.save(returnOrder), returnOrder.getReturnOrderItems());
    }

    @Transactional
    public ReturnOrderResponse cancel(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        ReturnOrder returnOrder = returnOrderRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RETURN_ORDER_NOT_FOUND, "Return order not found"));

        if (!"PENDING".equals(returnOrder.getStatus())) {
            throw new IllegalArgumentException("Return order is not in PENDING status");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());
        returnOrder.setStatus("CANCELLED");
        returnOrder.setLastModifiedByUser(userRef);
        returnOrder.setLastModifiedAt(LocalDateTime.now());
        returnOrder.setUpdatedAt(LocalDateTime.now());

        return toResponse(returnOrderRepository.save(returnOrder), List.of());
    }

    private void restoreInventory(Store store, Product product, Warehouse warehouse,
            BigDecimal quantity, User userRef, String returnCode) {
        Inventory inv = inventoryRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                .orElseGet(() -> Inventory.builder()
                        .product(product).warehouse(warehouse).store(store)
                        .quantity(BigDecimal.ZERO).publicId(UUID.randomUUID())
                        .lastModifiedByUser(userRef).build());

        inv.setQuantity(inv.getQuantity().add(quantity));
        inv.setLastModifiedAt(LocalDateTime.now());
        inv.setUpdatedAt(LocalDateTime.now());
        inv.setLastModifiedByUser(userRef);
        inventoryRepository.save(inv);

        inventoryTransactionRepository.save(InventoryTransaction.builder()
                .store(store).product(product).warehouse(warehouse)
                .type("IN").quantity(quantity)
                .note("Return: " + returnCode).createdBy(userRef)
                .build());
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private ReturnOrderResponse toResponse(ReturnOrder r, List<ReturnOrderItem> items) {
        return new ReturnOrderResponse(
                r.getId(), r.getPublicId(), r.getStore().getId(),
                r.getReturnCode(), r.getOriginalOrder().getPublicId(),
                r.getWarehouse().getPublicId(),
                r.getStatus(), r.getReason(), r.getTotalRefund(), r.getRefundMethod(),
                r.getNote(), r.getSyncVersion(), r.getLastModifiedAt(),
                r.getCreatedAt(), r.getUpdatedAt(),
                items.stream().map(this::toItemResponse).toList()
        );
    }

    private ReturnOrderItemResponse toItemResponse(ReturnOrderItem i) {
        return new ReturnOrderItemResponse(
                i.getId(), i.getProduct().getPublicId(), i.getProduct().getName(),
                i.getQuantity(), i.getUnitPrice(), i.getTotalRefund()
        );
    }
}
