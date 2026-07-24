package com.quiktech.backend.service;

import com.quiktech.backend.dto.request.order.OrderCreateRequest;
import com.quiktech.backend.dto.request.order.OrderItemRequest;
import com.quiktech.backend.dto.response.common.ErrorCode;
import com.quiktech.backend.dto.response.common.PagedResult;
import com.quiktech.backend.dto.response.order.OrderItemResponse;
import com.quiktech.backend.dto.response.order.OrderResponse;
import com.quiktech.backend.entity.*;
import com.quiktech.backend.entity.enums.DiscountType;
import com.quiktech.backend.entity.enums.InventoryTransactionType;
import com.quiktech.backend.entity.enums.OrderStatus;
import com.quiktech.backend.exception.ResourceNotFoundException;
import com.quiktech.backend.annotation.Auditable;
import com.quiktech.backend.repository.*;
import com.quiktech.backend.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PagedResult<OrderResponse> list(Long storeId, String orderCode, OrderStatus status, LocalDate from, LocalDate to, UUID customerPublicId, Pageable pageable, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        String codeFilter = (orderCode != null && !orderCode.isBlank()) ? "%" + orderCode.toLowerCase() + "%" : null;
        Instant fromInstant = from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.EPOCH;
        Instant toInstant = to != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.parse("9999-12-31T00:00:00Z");
        return PagedResult.of(
                orderRepository.search(storeId, status, codeFilter, customerPublicId, fromInstant, toInstant, pageable)
                        .map(o -> toResponse(o, List.of()))
        );
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Order order = orderRepository.findByPublicIdWithItems(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));
        return toResponse(order, order.getOrderItems());
    }

    @Auditable(action = "CREATE_ORDER", entityType = "ORDER")
    @Transactional
    public OrderResponse create(Long storeId, OrderCreateRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);
        Customer customer = resolveCustomer(store, request.customerPublicId());
        Warehouse warehouse = resolveWarehouse(storeId, request.warehousePublicId());
        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Build order shell with all references
        Order order = buildOrderShell(store, customer, warehouse, request, userRef);
        order = orderRepository.save(order); // persist before @Modifying flush in buildOrderItemsAndDeductInventory

        // Build items, compute subtotal, and deduct inventory
        List<OrderItem> items = buildOrderItemsAndDeductInventory(
                order, store, request.items(), warehouse, userRef
        );

        // Calculate totals
        BigDecimal subtotal = items.stream()
                .map(OrderItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal discountAmt = computeDiscount(subtotal, request.discount(), request.discountType());
        BigDecimal totalAmount = subtotal.subtract(discountAmt).add(request.tax())
                .setScale(2, RoundingMode.HALF_UP);
        // Giảm giá cấp đơn lớn hơn tiền hàng → tổng âm, chặn để báo cáo không nhận số âm
        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Total amount cannot be negative — order discount exceeds subtotal");
        }

        BigDecimal paidAmt = request.paidAmount() != null ? request.paidAmount() : BigDecimal.ZERO;
        if (paidAmt.compareTo(totalAmount) > 0) {
            throw new IllegalArgumentException("Paid amount cannot exceed total amount");
        }
        if (customer == null && totalAmount.subtract(paidAmt).compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException("Walk-in customer orders must be fully paid");
        }

        String paymentMethod = request.paymentMethod() != null
                ? request.paymentMethod().name() : "CASH";

        order.setSubtotal(subtotal);
        order.setTotalAmount(totalAmount);
        order.setPaidAmount(paidAmt);
        order.setDebtAmount(totalAmount.subtract(paidAmt));
        order.setPaymentMethod(paymentMethod);
        order.setOrderItems(items);

        return toResponse(orderRepository.save(order), items);
    }

    @Auditable(action = "COMPLETE_ORDER", entityType = "ORDER")
    @Transactional
    public OrderResponse complete(Long storeId, UUID publicId, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);
        Order order = orderRepository.findByPublicIdWithCustomer(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));

        validateOrderCanTransition(order);

        // Add debt to customer balance only when completing order
        if (order.getCustomer() != null && order.getDebtAmount().compareTo(BigDecimal.ZERO) > 0) {
            Customer customer = order.getCustomer();
            customer.setDebtBalance(customer.getDebtBalance().add(order.getDebtAmount()));
            customerRepository.save(customer);
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());
        order.setStatus(OrderStatus.COMPLETED);
        order.setLastModifiedByUser(userRef);
        order.setLastModifiedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        Order saved = orderRepository.save(order);

        if (saved.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            paymentRepository.save(Payment.builder()
                    .store(saved.getStore())
                    .customer(saved.getCustomer())
                    .amount(saved.getPaidAmount())
                    .paymentMethod(saved.getPaymentMethod())
                    .note("Order: " + saved.getOrderCode())
                    .publicId(UUID.randomUUID())
                    .lastModifiedByUser(userRef)
                    .createdBy(userRef)
                    .build());
        }

        return toResponse(saved, List.of());
    }

    @Auditable(action = "PAY_ORDER", entityType = "ORDER")
    @Transactional
    public OrderResponse pay(Long storeId, UUID publicId, BigDecimal amount, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        Order order = orderRepository.findByPublicIdWithCustomer(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));

        if (OrderStatus.CANCELLED.equals(order.getStatus())) {
            throw new IllegalArgumentException("Cannot pay a cancelled order");
        }
        if (amount.compareTo(order.getDebtAmount()) > 0) {
            throw new IllegalArgumentException("Payment amount exceeds remaining debt");
        }

        order.setPaidAmount(order.getPaidAmount().add(amount));
        order.setDebtAmount(order.getDebtAmount().subtract(amount));

        if (OrderStatus.COMPLETED.equals(order.getStatus()) && order.getCustomer() != null) {
            Customer customer = order.getCustomer();
            customer.setDebtBalance(customer.getDebtBalance().subtract(amount));
            customerRepository.save(customer);
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());
        order.setLastModifiedByUser(userRef);
        order.setLastModifiedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        Order saved = orderRepository.save(order);

        // Only record payment when order is already COMPLETED (post-completion debt payment)
        if (OrderStatus.COMPLETED.equals(saved.getStatus())) {
            paymentRepository.save(Payment.builder()
                    .store(saved.getStore())
                    .customer(saved.getCustomer())
                    .amount(amount)
                    .paymentMethod(saved.getPaymentMethod())
                    .note("Order: " + saved.getOrderCode())
                    .publicId(UUID.randomUUID())
                    .lastModifiedByUser(userRef)
                    .createdBy(userRef)
                    .build());
        }

        return toResponse(saved, List.of());
    }

    @Auditable(action = "CANCEL_ORDER", entityType = "ORDER")
    @Transactional
    public OrderResponse cancel(Long storeId, UUID publicId, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);
        Order order = orderRepository.findByPublicIdWithItems(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.ORDER_NOT_FOUND, "Order not found"));

        validateOrderCanTransition(order);

        // Đơn đã thu tiền (kể cả một phần) không được hủy trực tiếp — nếu hủy, khoản đã thu
        // biến mất khỏi sổ sách. Phải hoàn qua đơn trả hàng để ghi nhận hoàn tiền.
        if (order.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalArgumentException("Cannot cancel an order with recorded payment. Use a return order to refund instead");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Restore inventory for each item
        Set<Long> affectedProductIds = new java.util.HashSet<>();
        for (OrderItem item : order.getOrderItems()) {
            restoreInventory(order.getStore(), item.getProduct(), order.getWarehouse(),
                    item.getQuantity(), order, userRef);
            affectedProductIds.add(item.getProduct().getId());
        }
        affectedProductIds.forEach(productRepository::recalculateTotalStock);

        order.setStatus(OrderStatus.CANCELLED);
        order.setLastModifiedByUser(userRef);
        order.setLastModifiedAt(Instant.now());
        order.setUpdatedAt(Instant.now());

        return toResponse(orderRepository.save(order), order.getOrderItems());
    }

    private void deductInventory(Store store, Product product, Warehouse warehouse, BigDecimal quantity, Order order, User userRef) {
        Inventory inv = inventoryRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.INVENTORY_NOT_FOUND,
                        "No stock for '" + product.getName() + "' in selected warehouse"));

        if (inv.getQuantity().compareTo(quantity) < 0) {
            throw new IllegalArgumentException("Insufficient stock for product: " + product.getName());
        }

        BigDecimal previousQuantity = inv.getQuantity();
        inv.setQuantity(previousQuantity.subtract(quantity));
        inv.setLastModifiedAt(Instant.now());
        inv.setUpdatedAt(Instant.now());
        inv.setLastModifiedByUser(userRef);
        inventoryRepository.save(inv);

        inventoryTransactionRepository.save(InventoryTransaction.builder()
                .store(store)
                .product(product)
                .warehouse(warehouse)
                .type(InventoryTransactionType.OUT)
                .quantity(quantity)
                .previousQuantity(previousQuantity)
                .order(order)
                .note("Order: " + order.getOrderCode())
                .createdBy(userRef)
                .build());
    }

    private void restoreInventory(Store store, Product product, Warehouse warehouse,
            BigDecimal quantity, Order order, User userRef) {
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
                .type(InventoryTransactionType.IN).quantity(quantity).previousQuantity(previousQuantity).order(order)
                .note("Cancel order: " + order.getOrderCode()).createdBy(userRef)
                .build());
    }

    // ==== Helper Methods for create() ====

    // Customer thuộc scope business → đối chiếu theo business của store để chống IDOR
    private Customer resolveCustomer(Store store, UUID customerPublicId) {
        if (customerPublicId == null) {
            return null;  // Walk-in customer
        }
        return customerRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), customerPublicId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.CUSTOMER_NOT_FOUND, "Customer not found"));
    }

    // Mã 6 hex ngẫu nhiên có thể trùng (birthday) — check DB trước; unique (store_id, order_code) là backstop
    private String generateUniqueOrderCode(Long storeId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            if (orderRepository.findByStoreIdAndOrderCode(storeId, code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique order code after 5 attempts");
    }

    private Warehouse resolveWarehouse(Long storeId, UUID warehousePublicId) {
        Warehouse warehouse = warehouseRepository.findByPublicIdAndStoreId(warehousePublicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));
        if (!warehouse.getIsActive()) {
            throw new IllegalArgumentException("Warehouse is inactive");
        }
        return warehouse;
    }

    private Order buildOrderShell(Store store, Customer customer, Warehouse warehouse,
            OrderCreateRequest request, User userRef) {
        return Order.builder()
                .store(store)
                .orderCode(generateUniqueOrderCode(store.getId()))
                .customer(customer)
                .warehouse(warehouse)
                .status(OrderStatus.PENDING)
                .subtotal(BigDecimal.ZERO)
                .discount(request.discount())
                .discountType(DiscountType.valueOf(request.discountType()))
                .tax(request.tax())
                .totalAmount(BigDecimal.ZERO)
                .paidAmount(BigDecimal.ZERO)
                .debtAmount(BigDecimal.ZERO)
                .note(request.note())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();
    }

    private List<OrderItem> buildOrderItemsAndDeductInventory(Order order, Store store, List<OrderItemRequest> itemRequests,
            Warehouse warehouse, User userRef) {
        List<OrderItem> items = new ArrayList<>();
        Set<Long> affectedProductIds = new java.util.HashSet<>();

        for (OrderItemRequest itemReq : itemRequests) {
            // Product thuộc scope business → đối chiếu theo business của store để chống IDOR
            Product product = productRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), itemReq.productPublicId())
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
                    .discountType(DiscountType.valueOf(itemReq.discountType()))
                    .totalPrice(lineTotal)
                    .publicId(UUID.randomUUID())
                    .lastModifiedByUser(userRef)
                    .build();

            items.add(item);

            // Deduct inventory immediately when order is placed
            deductInventory(store, product, warehouse, itemReq.quantity(), order, userRef);
            affectedProductIds.add(product.getId());
        }
        affectedProductIds.forEach(productRepository::recalculateTotalStock);

        return items;
    }

    private void validateOrderCanTransition(Order order) {
        if (OrderStatus.COMPLETED.equals(order.getStatus())) {
            throw new IllegalArgumentException("Order is already completed");
        }
        if (OrderStatus.CANCELLED.equals(order.getStatus())) {
            throw new IllegalArgumentException("Order is already cancelled");
        }
    }

    // Tiền lưu ở scale 2 (cột NUMERIC(15,2)) → làm tròn HALF_UP ngay khi tính,
    // tránh sai lệch tích lũy giữa giá trị tính toán và giá trị lưu DB
    private BigDecimal computeLineTotal(BigDecimal unitPrice, BigDecimal quantity,
            BigDecimal discount, String discountType) {
        BigDecimal base = unitPrice.multiply(quantity);
        BigDecimal lineTotal;
        if ("PERCENT".equals(discountType)) {
            validatePercent(discount);
            lineTotal = base.multiply(BigDecimal.ONE.subtract(discount.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP)))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            lineTotal = base.subtract(discount).setScale(2, RoundingMode.HALF_UP);
        }
        // Giảm giá FIXED lớn hơn tiền hàng → dòng âm, chặn để không lọt doanh thu/công nợ âm
        if (lineTotal.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Line total cannot be negative — discount exceeds line amount");
        }
        return lineTotal;
    }

    private BigDecimal computeDiscount(BigDecimal subtotal, BigDecimal discount, String discountType) {
        if ("PERCENT".equals(discountType)) {
            validatePercent(discount);
            return subtotal.multiply(discount.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    private void validatePercent(BigDecimal discount) {
        if (discount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Percent discount cannot exceed 100");
        }
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
                o.getStatus().toString(), o.getSubtotal(), o.getDiscount(), o.getDiscountType().name(),
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
                i.getDiscount(), i.getDiscountType().name(), i.getTotalPrice(),
                i.getSyncVersion()
        );
    }
}
