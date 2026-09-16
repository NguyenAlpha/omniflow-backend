package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.purchase.PurchaseOrderCreateRequest;
import com.quiktech.pos.dto.request.purchase.PurchaseOrderItemRequest;
import com.quiktech.pos.dto.request.purchase.PurchaseOrderPayRequest;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.dto.response.common.PagedResult;
import com.quiktech.pos.dto.response.purchase.PurchaseOrderItemResponse;
import com.quiktech.pos.dto.response.purchase.PurchaseOrderResponse;
import com.quiktech.pos.entity.*;
import com.quiktech.pos.entity.enums.PurchaseOrderStatus;
import com.quiktech.pos.entity.enums.InventoryTransactionType;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.annotation.Auditable;
import com.quiktech.pos.repository.*;
import com.quiktech.pos.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PagedResult<PurchaseOrderResponse> list(Long storeId, String orderCode, PurchaseOrderStatus status, LocalDate from, LocalDate to, Pageable pageable, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        String codeFilter = (orderCode != null && !orderCode.isBlank()) ? "%" + orderCode.toLowerCase() + "%" : null;
        Instant fromInstant = from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.EPOCH;
        Instant toInstant = to != null ? to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.parse("9999-12-31T00:00:00Z");
        Page<PurchaseOrder> page = purchaseOrderRepository.search(storeId, status, codeFilter, fromInstant, toInstant, pageable);
        return PagedResult.of(page.map(po -> toResponse(po, List.of())));
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        PurchaseOrder po = purchaseOrderRepository.findByPublicIdWithItems(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "Purchase order not found"));
        return toResponse(po, po.getPurchaseOrderItems());
    }

    @Auditable(action = "CREATE_PURCHASE_ORDER", entityType = "PURCHASE_ORDER")
    @Transactional
    public PurchaseOrderResponse create(Long storeId, PurchaseOrderCreateRequest request, UserPrincipal currentUser) {
        Store store = findStoreOrThrow(storeId);

        // Supplier thuộc scope business, warehouse thuộc scope store → lookup scoped để chống IDOR
        Supplier supplier = supplierRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), request.supplierPublicId())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.SUPPLIER_NOT_FOUND, "Supplier not found"));

        Warehouse warehouse = warehouseRepository.findByPublicIdAndStoreId(request.warehousePublicId(), storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, "Warehouse not found"));
        if (!warehouse.getIsActive()) {
            throw new IllegalArgumentException("Warehouse is inactive");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        List<PurchaseOrderItem> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        PurchaseOrder po = PurchaseOrder.builder()
                .store(store)
                .orderCode(generateUniqueOrderCode(store.getId()))
                .supplier(supplier)
                .warehouse(warehouse)
                .status(PurchaseOrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .paidAmount(BigDecimal.ZERO)
                .debtAmount(BigDecimal.ZERO)
                .note(request.note())
                .publicId(UUID.randomUUID())
                .lastModifiedByUser(userRef)
                .createdBy(userRef)
                .build();

        for (PurchaseOrderItemRequest itemReq : request.items()) {
            // Product thuộc scope business → đối chiếu theo business của store để chống IDOR
            Product product = productRepository.findByBusinessIdAndPublicId(store.getBusiness().getId(), itemReq.productPublicId())
                    .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Product not found"));

            BigDecimal lineTotal = itemReq.unitPrice().multiply(itemReq.quantity());

            PurchaseOrderItem item = PurchaseOrderItem.builder()
                    .purchaseOrder(po)
                    .product(product)
                    .store(store)
                    .quantity(itemReq.quantity())
                    .unitPrice(itemReq.unitPrice())
                    .totalPrice(lineTotal)
                    .publicId(UUID.randomUUID())
                    .lastModifiedByUser(userRef)
                    .build();

            items.add(item);
            totalAmount = totalAmount.add(lineTotal);
        }

        BigDecimal paidAmt = request.paidAmount() != null ? request.paidAmount() : BigDecimal.ZERO;
        if (paidAmt.compareTo(totalAmount) > 0) {
            throw new IllegalArgumentException("Paid amount cannot exceed total amount");
        }
        String paymentMethod = request.paymentMethod() != null
                ? request.paymentMethod().name() : "CASH";

        po.setTotalAmount(totalAmount);
        po.setPaidAmount(paidAmt);
        po.setDebtAmount(totalAmount.subtract(paidAmt));
        po.setPaymentMethod(paymentMethod);
        po.setPurchaseOrderItems(items);

        return toResponse(purchaseOrderRepository.save(po), items);
    }

    @Auditable(action = "RECEIVE_PURCHASE_ORDER", entityType = "PURCHASE_ORDER")
    @Transactional
    public PurchaseOrderResponse receive(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        PurchaseOrder po = purchaseOrderRepository.findByPublicIdWithItems(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "Purchase order not found"));

        if (po.getStatus() != PurchaseOrderStatus.PENDING) {
            throw new IllegalArgumentException("Purchase order is not in PENDING status");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());

        // Add goods to inventory
        Set<Long> affectedProductIds = new java.util.HashSet<>();
        for (PurchaseOrderItem item : po.getPurchaseOrderItems()) {
            addToInventory(po.getStore(), item.getProduct(), po.getWarehouse(),
                    item.getQuantity(), po, userRef);
            affectedProductIds.add(item.getProduct().getId());
        }
        affectedProductIds.forEach(productRepository::recalculateTotalStock);

        // Add debt to supplier balance
        if (po.getDebtAmount().compareTo(BigDecimal.ZERO) > 0) {
            Supplier supplier = po.getSupplier();
            supplier.setDebtBalance(supplier.getDebtBalance().add(po.getDebtAmount()));
            supplierRepository.save(supplier);
        }

        po.setStatus(PurchaseOrderStatus.RECEIVED);
        po.setLastModifiedByUser(userRef);
        po.setLastModifiedAt(Instant.now());
        po.setUpdatedAt(Instant.now());

        PurchaseOrder saved = purchaseOrderRepository.save(po);

        if (saved.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            paymentRepository.save(Payment.builder()
                    .store(saved.getStore())
                    .supplier(saved.getSupplier())
                    .amount(saved.getPaidAmount())
                    .paymentMethod(saved.getPaymentMethod())
                    .note("Purchase order: " + saved.getOrderCode())
                    .publicId(UUID.randomUUID())
                    .lastModifiedByUser(userRef)
                    .createdBy(userRef)
                    .build());
        }

        return toResponse(saved, saved.getPurchaseOrderItems());
    }

    @Auditable(action = "CANCEL_PURCHASE_ORDER", entityType = "PURCHASE_ORDER")
    @Transactional
    public PurchaseOrderResponse cancel(Long storeId, UUID publicId, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        PurchaseOrder po = purchaseOrderRepository.findByPublicIdAndStoreId(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "Purchase order not found"));

        if (po.getStatus() != PurchaseOrderStatus.PENDING) {
            throw new IllegalArgumentException("Purchase order is not in PENDING status");
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        po.setLastModifiedByUser(userRef);
        po.setLastModifiedAt(Instant.now());
        po.setUpdatedAt(Instant.now());

        return toResponse(purchaseOrderRepository.save(po), List.of());
    }

    @Transactional
    public PurchaseOrderResponse pay(Long storeId, UUID publicId, BigDecimal amount, UserPrincipal currentUser) {
        findStoreOrThrow(storeId);
        PurchaseOrder po = purchaseOrderRepository.findByPublicIdAndStoreId(publicId, storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "Purchase order not found"));

        if (po.getStatus() == PurchaseOrderStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot pay a cancelled purchase order");
        }
        if (amount.compareTo(po.getDebtAmount()) > 0) {
            throw new IllegalArgumentException("Payment amount exceeds remaining debt");
        }

        po.setPaidAmount(po.getPaidAmount().add(amount));
        po.setDebtAmount(po.getDebtAmount().subtract(amount));

        if (po.getStatus() == PurchaseOrderStatus.RECEIVED) {
            Supplier supplier = po.getSupplier();
            supplier.setDebtBalance(supplier.getDebtBalance().subtract(amount));
            supplierRepository.save(supplier);
        }

        User userRef = userRepository.getReferenceById(currentUser.userId());
        po.setLastModifiedByUser(userRef);
        po.setLastModifiedAt(Instant.now());
        po.setUpdatedAt(Instant.now());

        PurchaseOrder saved = purchaseOrderRepository.save(po);

        if (saved.getStatus() == PurchaseOrderStatus.RECEIVED) {
            paymentRepository.save(Payment.builder()
                    .store(saved.getStore())
                    .supplier(saved.getSupplier())
                    .amount(amount)
                    .paymentMethod(saved.getPaymentMethod())
                    .note("Purchase order: " + saved.getOrderCode())
                    .publicId(UUID.randomUUID())
                    .lastModifiedByUser(userRef)
                    .createdBy(userRef)
                    .build());
        }

        return toResponse(saved, List.of());
    }

    private void addToInventory(Store store, Product product, Warehouse warehouse,
            BigDecimal quantity, PurchaseOrder po, User userRef) {
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
                .type(InventoryTransactionType.IN).quantity(quantity).previousQuantity(previousQuantity).purchaseOrder(po)
                .note("Receive PO: " + po.getOrderCode()).createdBy(userRef)
                .build());
    }

    private Store findStoreOrThrow(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.STORE_NOT_FOUND, "Store not found"));
    }

    // Mã 6 hex ngẫu nhiên có thể trùng (birthday) — check DB trước; unique (store_id, order_code) là backstop
    private String generateUniqueOrderCode(Long storeId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "PO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            if (purchaseOrderRepository.findByStoreIdAndOrderCode(storeId, code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Could not generate a unique order code after 5 attempts");
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder po, List<PurchaseOrderItem> items) {
        return new PurchaseOrderResponse(
                po.getId(), po.getPublicId(), po.getStore().getId(),
                po.getOrderCode(), po.getSupplier().getPublicId(), po.getSupplier().getName(),
                po.getWarehouse().getPublicId(), po.getWarehouse().getName(),
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
