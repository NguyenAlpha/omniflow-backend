package com.omniflow.backend.service;

import com.omniflow.backend.dto.request.order.OrderCreateRequest;
import com.omniflow.backend.dto.request.order.OrderItemRequest;
import com.omniflow.backend.dto.response.common.ErrorCode;
import com.omniflow.backend.dto.response.order.OrderItemResponse;
import com.omniflow.backend.dto.response.order.OrderResponse;
import com.omniflow.backend.entity.*;
import com.omniflow.backend.exception.ResourceNotFoundException;
import com.omniflow.backend.repository.*;
import com.omniflow.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<OrderResponse> list(Long storeId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        return orderRepository.findByStoreIdOrderByCreatedAtDesc(storeId)
                .stream().map(o -> toResponse(o, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Order order = orderRepository.findByPublicIdWithItems(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        return toResponse(order, order.getOrderItems());
    }

    @Transactional
    public OrderResponse create(Long storeId, OrderCreateRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        if (orderRepository.findByStoreIdAndOrderCode(storeId, request.orderCode()).isPresent()) {
            throw new IllegalArgumentException("Order code already exists in this store");
        }

        Customer customer = null;
        if (request.customerPublicId() != null) {
            customer = customerRepository.findByPublicId(request.customerPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found"));
        }

        Warehouse warehouse = warehouseRepository.findByPublicId(request.warehousePublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));

        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Build order shell first so items can reference it
        Order order = Order.builder()
                .store(store)
                .orderCode(request.orderCode())
                .customer(customer)
                .warehouse(warehouse)
                .status("PENDING")
                .subtotal(BigDecimal.ZERO)
                .discount(request.discount())
                .discountType(request.discountType())
                .tax(request.tax())
                .totalAmount(BigDecimal.ZERO)
                .paidAmount(BigDecimal.ZERO)
                .debtAmount(BigDecimal.ZERO)
                .note(request.note())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        // Build items and compute totals
        List<OrderItem> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (OrderItemRequest itemReq : request.items()) {
            Product product = productRepository.findByPublicId(itemReq.productPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

            BigDecimal lineTotal = computeLineTotal(itemReq.unitPrice(), itemReq.quantity(),
                    itemReq.discount(), itemReq.discountType());

            OrderItem item = OrderItem.builder()
                    .order(order)
                    .product(product)
                    .store(store)
                    .quantity(itemReq.quantity())
                    .unitPrice(itemReq.unitPrice())
                    .discount(itemReq.discount())
                    .discountType(itemReq.discountType())
                    .totalPrice(lineTotal)
                    .publicId(UUID.randomUUID())
                    .lastModifiedByUser(userRef)
                    .build();

            items.add(item);
            subtotal = subtotal.add(lineTotal);

            // Deduct inventory immediately when order is placed
            deductInventory(store, product, warehouse, itemReq.quantity(), order, userRef);
        }

        BigDecimal discountAmt = computeDiscount(subtotal, request.discount(), request.discountType());
        BigDecimal totalAmount = subtotal.subtract(discountAmt).add(request.tax());

        order.setSubtotal(subtotal);
        order.setTotalAmount(totalAmount);
        order.setDebtAmount(totalAmount);
        order.setOrderItems(items);

        Order saved = orderRepository.save(order);
        return toResponse(saved, items);
    }

    @Transactional
    public OrderResponse complete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Order order = orderRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));

        if ("COMPLETED".equals(order.getStatus())) {
            throw new IllegalArgumentException("Order is already completed");
        }
        if ("CANCELLED".equals(order.getStatus())) {
            throw new IllegalArgumentException("Order is already cancelled");
        }

        // Add debt to customer balance
        if (order.getCustomer() != null && order.getDebtAmount().compareTo(BigDecimal.ZERO) > 0) {
            Customer customer = order.getCustomer();
            customer.setDebtBalance(customer.getDebtBalance().add(order.getDebtAmount()));
            customerRepository.save(customer);
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());
        order.setStatus("COMPLETED");
        order.setLastModifiedByUser(userRef);
        order.setLastModifiedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        return toResponse(orderRepository.save(order), List.of());
    }

    @Transactional
    public OrderResponse cancel(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Order order = orderRepository.findByPublicIdWithItems(publicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));

        if ("COMPLETED".equals(order.getStatus())) {
            throw new IllegalArgumentException("Order is already completed");
        }
        if ("CANCELLED".equals(order.getStatus())) {
            throw new IllegalArgumentException("Order is already cancelled");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Restore inventory for each item
        for (OrderItem item : order.getOrderItems()) {
            restoreInventory(order.getStore(), item.getProduct(), order.getWarehouse(),
                    item.getQuantity(), order, userRef);
        }

        order.setStatus("CANCELLED");
        order.setLastModifiedByUser(userRef);
        order.setLastModifiedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        return toResponse(orderRepository.save(order), order.getOrderItems());
    }

    private void deductInventory(Store store, Product product, Warehouse warehouse,
            BigDecimal quantity, Order order, User userRef) {
        Inventory inv = inventoryRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.INVENTORY_NOT_FOUND,
                        "No stock for '" + product.getName() + "' in selected warehouse"));

        if (inv.getQuantity().compareTo(quantity) < 0) {
            throw new IllegalArgumentException("Insufficient stock for product: " + product.getName());
        }

        inv.setQuantity(inv.getQuantity().subtract(quantity));
        inv.setLastModifiedAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inv.setLastModifiedByUser(userRef);
        inventoryRepository.save(inv);

        inventoryTransactionRepository.save(InventoryTransaction.builder()
                .store(store).product(product).warehouse(warehouse)
                .type("OUT").quantity(quantity).order(order)
                .note("Order: " + order.getOrderCode()).createdBy(userRef)
                .build());
    }

    private void restoreInventory(Store store, Product product, Warehouse warehouse,
            BigDecimal quantity, Order order, User userRef) {
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
                .type("IN").quantity(quantity).order(order)
                .note("Cancel order: " + order.getOrderCode()).createdBy(userRef)
                .build());
    }

    private BigDecimal computeLineTotal(BigDecimal unitPrice, BigDecimal quantity,
            BigDecimal discount, String discountType) {
        BigDecimal base = unitPrice.multiply(quantity);
        if ("PERCENT".equals(discountType)) {
            return base.multiply(BigDecimal.ONE.subtract(discount.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)));
        }
        return base.subtract(discount);
    }

    private BigDecimal computeDiscount(BigDecimal subtotal, BigDecimal discount, String discountType) {
        if ("PERCENT".equals(discountType)) {
            return subtotal.multiply(discount.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        }
        return discount;
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    private OrderResponse toResponse(Order o, List<OrderItem> items) {
        return new OrderResponse(
                o.getId(), o.getPublicId(), o.getStore().getId(),
                o.getOrderCode(),
                o.getCustomer() != null ? o.getCustomer().getPublicId() : null,
                o.getWarehouse().getPublicId(),
                o.getStatus(), o.getSubtotal(), o.getDiscount(), o.getDiscountType(),
                o.getTax(), o.getTotalAmount(), o.getPaidAmount(), o.getDebtAmount(),
                o.getNote(), o.getSyncVersion(), o.getLastModifiedAt(),
                o.getCreatedAt(), o.getUpdatedAt(),
                items.stream().map(this::toItemResponse).toList()
        );
    }

    private OrderItemResponse toItemResponse(OrderItem i) {
        return new OrderItemResponse(
                i.getId(), i.getPublicId(),
                i.getProduct().getPublicId(), i.getProduct().getName(),
                i.getQuantity(), i.getUnitPrice(),
                i.getDiscount(), i.getDiscountType(), i.getTotalPrice(),
                i.getSyncVersion()
        );
    }
}
