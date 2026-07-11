package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.order.ReturnOrderCreateRequest;
import com.quiktech.backend.dto.request.order.ReturnOrderItemRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.order.ReturnOrderItemResponse;
import com.quiktech.backend.dto.response.order.ReturnOrderResponse;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.entity.enums.InventoryTransactionType;
import com.quiktech.backend.entity.enums.RefundMethod;
import com.quiktech.backend.entity.enums.ReturnOrderStatus;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        ReturnOrder returnOrder = returnOrderRepository.findByPublicIdWithItems(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RETURN_ORDER_NOT_FOUND, "Return order not found"));
        return toResponse(returnOrder, returnOrder.getReturnOrderItems());
    }

    @Transactional
    public ReturnOrderResponse create(Long storeId, ReturnOrderCreateRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (returnOrderRepository.findByStoreIdAndReturnCode(storeId, request.returnCode()).isPresent()) {
            throw new IllegalArgumentException("Return code already exists in this store");
        }

        // Lookup scoped theo store để chống IDOR (đơn gốc/kho của tenant khác → 404)
        Order originalOrder = orderRepository.findByPublicIdAndStoreId(request.originalOrderPublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Original order not found"));

        Warehouse warehouse = warehouseRepository.findByPublicIdAndStoreId(request.warehousePublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));

        User userRef = userRepository.getReferenceById(currentUser.userId());

        ReturnOrder returnOrder = ReturnOrder.builder()
                .store(store)
                .returnCode(request.returnCode())
                .originalOrder(originalOrder)
                .warehouse(warehouse)
                .status(ReturnOrderStatus.PENDING)
                .reason(request.reason())
                .totalRefund(BigDecimal.ZERO)
                .refundMethod(RefundMethod.valueOf(request.refundMethod()))
                .note(request.note())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        List<ReturnOrderItem> items = new ArrayList<>();
        BigDecimal totalRefund = BigDecimal.ZERO;

        for (ReturnOrderItemRequest itemReq : request.items()) {
            // Product thuộc scope business → đối chiếu theo business của store để chống IDOR
            Product product = productRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), itemReq.productPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

            BigDecimal itemRefund = itemReq.unitPrice().multiply(itemReq.quantity());

            ReturnOrderItem item = ReturnOrderItem.builder()
                    .store(store)
                    .returnOrder(returnOrder)
                    .product(product)
                    .quantity(itemReq.quantity())
                    .unitPrice(itemReq.unitPrice())
                    .totalRefund(itemRefund)
                    .publicId(UUID.randomUUID())
                    .lastModifiedByUser(userRef)
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
        ReturnOrder returnOrder = returnOrderRepository.findByPublicIdWithItems(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RETURN_ORDER_NOT_FOUND, "Return order not found"));

        if (returnOrder.getStatus() != ReturnOrderStatus.PENDING) {
            throw new IllegalArgumentException("Return order is not in PENDING status");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Restore inventory for each returned item
        Set<Long> affectedProductIds = new java.util.HashSet<>();
        for (ReturnOrderItem item : returnOrder.getReturnOrderItems()) {
            restoreInventory(returnOrder.getStore(), item.getProduct(), returnOrder.getWarehouse(),
                    item.getQuantity(), userRef, returnOrder.getReturnCode());
            affectedProductIds.add(item.getProduct().getId());
        }
        affectedProductIds.forEach(productRepository::recalculateTotalStock);

        // Reduce customer debt and original order debt if applicable
        Order originalOrder = returnOrder.getOriginalOrder();
        Customer customer = originalOrder.getCustomer();
        if (customer != null && returnOrder.getTotalRefund().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal refund = returnOrder.getTotalRefund();

            BigDecimal newCustomerDebt = customer.getDebtBalance().subtract(refund).max(BigDecimal.ZERO);
            customer.setDebtBalance(newCustomerDebt);
            customerRepository.save(customer);

            if (originalOrder.getDebtAmount().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal applied = refund.min(originalOrder.getDebtAmount());
                originalOrder.setDebtAmount(originalOrder.getDebtAmount().subtract(applied));
                originalOrder.setLastModifiedAt(Instant.now());
                originalOrder.setUpdatedAt(Instant.now());
                orderRepository.save(originalOrder);
            }
        }

        returnOrder.setStatus(ReturnOrderStatus.COMPLETED);
        returnOrder.setLastModifiedByUser(userRef);
        returnOrder.setLastModifiedAt(Instant.now());
        returnOrder.setUpdatedAt(Instant.now());

        return toResponse(returnOrderRepository.save(returnOrder), returnOrder.getReturnOrderItems());
    }

    @Transactional
    public ReturnOrderResponse cancel(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        ReturnOrder returnOrder = returnOrderRepository.findByPublicIdAndStoreId(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.RETURN_ORDER_NOT_FOUND, "Return order not found"));

        if (returnOrder.getStatus() != ReturnOrderStatus.PENDING) {
            throw new IllegalArgumentException("Return order is not in PENDING status");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());
        returnOrder.setStatus(ReturnOrderStatus.CANCELLED);
        returnOrder.setLastModifiedByUser(userRef);
        returnOrder.setLastModifiedAt(Instant.now());
        returnOrder.setUpdatedAt(Instant.now());

        return toResponse(returnOrderRepository.save(returnOrder), List.of());
    }

    private void restoreInventory(Store store, Product product, Warehouse warehouse,
            BigDecimal quantity, User userRef, String returnCode) {
        Inventory inv = inventoryRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                .orElseGet(() -> Inventory.builder()
                        .product(product).warehouse(warehouse).store(store)
                        .quantity(BigDecimal.ZERO).publicId(UUID.randomUUID())
                        .lastModifiedByUser(userRef).build());

        BigDecimal previousQuantity = inv.getQuantity();
        inv.setQuantity(previousQuantity.add(quantity));
        inv.setLastModifiedAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inv.setLastModifiedByUser(userRef);
        inventoryRepository.save(inv);

        inventoryTransactionRepository.save(InventoryTransaction.builder()
                .store(store).product(product).warehouse(warehouse)
                .type(InventoryTransactionType.IN).quantity(quantity).previousQuantity(previousQuantity)
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
                r.getStatus().name(), r.getReason(), r.getTotalRefund(), r.getRefundMethod().name(),
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
