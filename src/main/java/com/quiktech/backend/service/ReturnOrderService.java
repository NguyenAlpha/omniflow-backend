package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.order.ReturnOrderCreateRequest;
import com.quiktech.backend.dto.request.order.ReturnOrderItemRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.order.ReturnOrderItemResponse;
import com.quiktech.backend.dto.response.order.ReturnOrderResponse;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.entity.enums.InventoryTransactionType;
import com.quiktech.backend.entity.enums.OrderStatus;
import com.quiktech.backend.entity.enums.RefundMethod;
import com.quiktech.backend.entity.enums.ReturnOrderStatus;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReturnOrderService {

    private final ReturnOrderRepository returnOrderRepository;
    private final OrderRepository orderRepository;
    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final PaymentRepository paymentRepository;
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

        // Lookup scoped theo store để chống IDOR (đơn gốc của tenant khác → 404)
        Order originalOrder = orderRepository.findByPublicIdWithItems(request.originalOrderPublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Original order not found"));

        if (originalOrder.getStatus() != OrderStatus.COMPLETED) {
            throw new IllegalArgumentException("Only COMPLETED orders can be returned");
        }

        // Kho nhập hàng trả = kho xuất của đơn gốc, không cho client tự chọn
        Warehouse warehouse = originalOrder.getWarehouse();

        // Gộp số lượng/thành tiền theo product của đơn gốc (một product có thể nằm trên nhiều dòng)
        Map<UUID, BigDecimal> purchasedQty = new HashMap<>();
        Map<UUID, BigDecimal> purchasedTotal = new HashMap<>();
        Map<UUID, Product> orderProducts = new HashMap<>();
        for (OrderItem oi : originalOrder.getOrderItems()) {
            UUID pid = oi.getProduct().getPublicId();
            purchasedQty.merge(pid, oi.getQuantity(), BigDecimal::add);
            purchasedTotal.merge(pid, oi.getTotalPrice(), BigDecimal::add);
            orderProducts.putIfAbsent(pid, oi.getProduct());
        }

        // Số lượng đã trả trước đó (không tính đơn trả đã hủy) để chặn trả vượt số đã mua
        Map<UUID, BigDecimal> alreadyReturned = new HashMap<>();
        for (ReturnOrder prev : returnOrderRepository.findByOriginalOrderId(originalOrder.getId())) {
            if (prev.getStatus() == ReturnOrderStatus.CANCELLED) continue;
            for (ReturnOrderItem prevItem : prev.getReturnOrderItems()) {
                alreadyReturned.merge(prevItem.getProduct().getPublicId(), prevItem.getQuantity(), BigDecimal::add);
            }
        }

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
        Map<UUID, BigDecimal> requestedQty = new HashMap<>();

        for (ReturnOrderItemRequest itemReq : request.items()) {
            UUID pid = itemReq.productPublicId();
            // Product phải nằm trong đơn gốc — vừa validate nghiệp vụ vừa chống IDOR
            Product product = orderProducts.get(pid);
            if (product == null) {
                throw new IllegalArgumentException("Product is not part of the original order");
            }

            BigDecimal purchased = purchasedQty.get(pid);
            BigDecimal requested = requestedQty.merge(pid, itemReq.quantity(), BigDecimal::add);
            BigDecimal returned = alreadyReturned.getOrDefault(pid, BigDecimal.ZERO);
            if (returned.add(requested).compareTo(purchased) > 0) {
                throw new IllegalArgumentException(
                        "Return quantity exceeds purchased quantity for product " + product.getSku());
            }

            // Đơn giá hoàn = giá hiệu dụng sau chiết khấu dòng (totalPrice/quantity),
            // không nhận từ client để tránh hoàn vượt số tiền đã trả
            BigDecimal effectiveUnitPrice = purchasedTotal.get(pid)
                    .divide(purchased, 2, RoundingMode.HALF_UP);
            BigDecimal itemRefund = effectiveUnitPrice.multiply(itemReq.quantity())
                    .setScale(2, RoundingMode.HALF_UP);

            ReturnOrderItem item = ReturnOrderItem.builder()
                    .store(store)
                    .returnOrder(returnOrder)
                    .product(product)
                    .quantity(itemReq.quantity())
                    .unitPrice(effectiveUnitPrice)
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
        BigDecimal refund = returnOrder.getTotalRefund();
        if (refund.compareTo(BigDecimal.ZERO) > 0) {
            // Chỉ trừ nợ đúng phần còn nợ trên đơn gốc; phần vượt là hoàn tiền mặt/chuyển khoản
            BigDecimal appliedToDebt = BigDecimal.ZERO;
            if (customer != null && originalOrder.getDebtAmount().compareTo(BigDecimal.ZERO) > 0) {
                appliedToDebt = refund.min(originalOrder.getDebtAmount());
                originalOrder.setDebtAmount(originalOrder.getDebtAmount().subtract(appliedToDebt));
                originalOrder.setLastModifiedAt(Instant.now());
                originalOrder.setUpdatedAt(Instant.now());
                orderRepository.save(originalOrder);

                customer.setDebtBalance(customer.getDebtBalance().subtract(appliedToDebt));
                customerRepository.save(customer);
            }

            // Ghi nhận phần hoàn tiền thực chi bằng Payment âm — supplier IS NULL nên rơi vào
            // nhóm INCOME của sổ quỹ, sumIncome tự trừ đi khoản hoàn
            BigDecimal cashRefund = refund.subtract(appliedToDebt);
            if (cashRefund.compareTo(BigDecimal.ZERO) > 0) {
                paymentRepository.save(Payment.builder()
                        .store(returnOrder.getStore())
                        .customer(customer)
                        .amount(cashRefund.negate())
                        .paymentMethod(returnOrder.getRefundMethod().name())
                        .note("Return: " + returnOrder.getReturnCode())
                        .publicId(UUID.randomUUID())
                        .lastModifiedByUser(userRef)
                        .createdBy(userRef)
                        .build());
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
