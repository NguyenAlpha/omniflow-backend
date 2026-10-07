package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.*;
import com.quiktech.pos.entity.enums.InventoryTransactionType;
import com.quiktech.pos.entity.enums.PurchaseOrderStatus;
import com.quiktech.pos.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@Order(6)
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderSeeder implements ApplicationRunner {

    private static final ZoneId SEED_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // Chỉnh hai mốc tại đây; ngày nhận hàng/thanh toán cũng nằm trong khoảng này.
    private static final int FROM_MONTHS_AGO = 6;
    private static final int TO_MONTHS_AGO = 4;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StoreRepository storeRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final PaymentRepository paymentRepository;

    @Value("#{${seed.enabled:false} and ${purchase-order.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Purchase order seeder is disabled");
            return;
        }
        seedAt(LocalDate.now(SEED_ZONE));
    }

    // Nhận ngày gốc riêng để kiểm thử khoảng lịch sử mà không phụ thuộc đồng hồ máy.
    void seedAt(LocalDate seedDate) {
        LocalDate from = seedDate.minusMonths(FROM_MONTHS_AGO);
        LocalDate to = seedDate.minusMonths(TO_MONTHS_AGO);
        long availableDays = ChronoUnit.DAYS.between(from, to) - 2;
        if (availableDays < 0) {
            throw new IllegalStateException("Purchase order seed window must allow at least two days for receiving goods");
        }

        List<Sample> samples = List.of(
                new Sample("PO-SEED-001", "SUP-001", PurchaseOrderStatus.PENDING, BigDecimal.ZERO, "CASH"),
                new Sample("PO-SEED-002", "SUP-002", PurchaseOrderStatus.CANCELLED, BigDecimal.ZERO, "CASH"),
                new Sample("PO-SEED-003", "SUP-003", PurchaseOrderStatus.RECEIVED, BigDecimal.ZERO, "CASH"),
                new Sample("PO-SEED-004", "SUP-001", PurchaseOrderStatus.RECEIVED, new BigDecimal("0.40"), "BANK_TRANSFER"),
                new Sample("PO-SEED-005", "SUP-002", PurchaseOrderStatus.RECEIVED, BigDecimal.ONE, "CASH")
        );

        Set<Long> affectedProducts = new HashSet<>();
        SeedSummary summary = new SeedSummary();
        seedStore("Business One", "Store One", "user1", samples, from, availableDays, affectedProducts, summary);
        seedStore("Business Two", "Store Two", "user2", samples, from, availableDays, affectedProducts, summary);

        // Flush inventory trước native query để totalStock đọc đúng số lượng vừa cộng.
        if (!affectedProducts.isEmpty()) {
            inventoryRepository.flush();
            affectedProducts.forEach(productRepository::recalculateTotalStock);
        }
        purchaseOrderRepository.flush();
        summary.logAfterCommit(log, "Purchase order", "from=" + from + ", to=" + to);
    }

    private void seedStore(String businessName, String storeName, String username, List<Sample> samples,
                           LocalDate from, long availableDays, Set<Long> affectedProducts, SeedSummary summary) {
        Store store = storeRepository.findByNameAndDeletedAtIsNull(storeName)
                .orElseThrow(() -> new IllegalStateException("Store not found: " + storeName + " — run StoreSeeder first"));
        if (!businessName.equals(store.getBusiness().getName())) {
            throw new IllegalStateException("Seed store does not belong to " + businessName + ": " + storeName);
        }

        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            // Check trước mọi side effect; giữ nguyên ngày và số tiền của đơn đã tồn tại.
            if (purchaseOrderRepository.findByStoreIdAndOrderCode(store.getId(), sample.code()).isPresent()) {
                summary.record(false);
                continue;
            }

            Warehouse warehouse = warehouseRepository.findByStoreIdAndNameAndDeletedAtIsNull(store.getId(), "Kho chính")
                    .orElseThrow(() -> new IllegalStateException("Warehouse not found for " + storeName + " — run WarehouseSeeder first"));
            if (!Boolean.TRUE.equals(warehouse.getIsActive())) {
                throw new IllegalStateException("Seed warehouse is inactive: " + storeName);
            }
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalStateException("User not found: " + username + " — run UserSeeder first"));
            Supplier supplier = supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(store.getBusiness().getId(), sample.supplierCode())
                    .orElseThrow(() -> new IllegalStateException("Supplier not found: " + businessName + "/" + sample.supplierCode() + " — run SupplierSeeder first"));

            Instant createdAt = from.plusDays(availableDays * i / (samples.size() - 1))
                    .atTime(9 + i, 15).atZone(SEED_ZONE).toInstant();
            Instant eventAt = switch (sample.status()) {
                case PENDING -> createdAt;
                case CANCELLED -> createdAt.plus(1, ChronoUnit.DAYS);
                case RECEIVED -> createdAt.plus(2, ChronoUnit.DAYS);
            };
            seedOrder(store, warehouse, supplier, user, sample, i, createdAt, eventAt, affectedProducts);
            summary.record(true);
        }
    }

    private void seedOrder(Store store, Warehouse warehouse, Supplier supplier, User user, Sample sample,
                           int index, Instant createdAt, Instant eventAt, Set<Long> affectedProducts) {
        PurchaseOrder po = PurchaseOrder.builder()
                .store(store).warehouse(warehouse).supplier(supplier)
                .orderCode(sample.code()).status(sample.status()).paymentMethod(sample.paymentMethod())
                .note("Historical sample purchase order")
                .publicId(UUID.randomUUID()).createdBy(user).lastModifiedByUser(user)
                .createdAt(createdAt).updatedAt(eventAt).lastModifiedAt(eventAt)
                .build();

        List<PurchaseOrderItem> items = new ArrayList<>();
        for (String sku : List.of("SP001", "SP002")) {
            Product product = productRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(store.getBusiness().getId(), sku)
                    .orElseThrow(() -> new IllegalStateException("Product not found: " + store.getBusiness().getName() + "/" + sku + " — run ProductSeeder first"));
            BigDecimal quantity = BigDecimal.valueOf((index + 1L) * (items.isEmpty() ? 10 : 5));
            items.add(PurchaseOrderItem.builder()
                    .purchaseOrder(po).store(store).product(product)
                    .quantity(quantity).unitPrice(product.getCostPrice())
                    .totalPrice(product.getCostPrice().multiply(quantity))
                    .publicId(UUID.randomUUID()).lastModifiedByUser(user).lastModifiedAt(createdAt)
                    .build());
        }
        BigDecimal total = items.stream().map(PurchaseOrderItem::getTotalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = total.multiply(sample.paidRatio()).setScale(2, java.math.RoundingMode.HALF_UP);
        po.setPurchaseOrderItems(items);
        po.setTotalAmount(total);
        po.setPaidAmount(paid);
        po.setDebtAmount(total.subtract(paid));
        // Lưu trạng thái cuối ngay lần INSERT: tránh @PreUpdate ghi đè ngày lịch sử bằng now().
        // save() có thể merge và trả về instance managed khác với po ban đầu.
        // Các liên kết tạo tiếp phải dùng instance và items được trả về.
        PurchaseOrder saved = purchaseOrderRepository.save(po);

        if (sample.status() != PurchaseOrderStatus.RECEIVED) return;

        for (PurchaseOrderItem item : saved.getPurchaseOrderItems()) {
            receiveItem(saved, item, user, eventAt);
            affectedProducts.add(item.getProduct().getId());
        }
        if (saved.getDebtAmount().signum() > 0) {
            supplier.setDebtBalance(supplier.getDebtBalance().add(saved.getDebtAmount()));
            supplier.setLastModifiedByUser(user);
            supplier.setLastModifiedAt(Instant.now());
            supplierRepository.save(supplier);
        }
        if (paid.signum() > 0) {
            paymentRepository.save(Payment.builder()
                    .store(store).supplier(supplier).amount(paid).paymentMethod(sample.paymentMethod())
                    .note("Purchase order: " + saved.getOrderCode())
                    .publicId(UUID.randomUUID()).createdBy(user).lastModifiedByUser(user)
                    .createdAt(eventAt).updatedAt(eventAt).lastModifiedAt(eventAt)
                    .build());
        }
    }

    private void receiveItem(PurchaseOrder po, PurchaseOrderItem item, User user, Instant receivedAt) {
        Inventory inventory = inventoryRepository.findByProductIdAndWarehouseId(item.getProduct().getId(), po.getWarehouse().getId())
                .orElseGet(() -> Inventory.builder()
                        .store(po.getStore()).warehouse(po.getWarehouse()).product(item.getProduct())
                        .publicId(UUID.randomUUID()).build());
        BigDecimal previous = inventory.getQuantity();
        inventory.setQuantity(previous.add(item.getQuantity()));
        // Inventory/Supplier là số dư hiện tại; lịch sử nằm ở PO, Payment và InventoryTransaction.
        inventory.setLastModifiedByUser(user);
        inventory.setLastModifiedAt(Instant.now());
        inventory.setUpdatedAt(Instant.now());
        inventoryRepository.save(inventory);

        inventoryTransactionRepository.save(InventoryTransaction.builder()
                .store(po.getStore()).warehouse(po.getWarehouse()).product(item.getProduct())
                .purchaseOrder(po).type(InventoryTransactionType.IN)
                .quantity(item.getQuantity()).previousQuantity(previous)
                .note("Receive PO: " + po.getOrderCode()).createdBy(user)
                .createdAt(receivedAt).updatedAt(receivedAt).build());
    }

    private record Sample(String code, String supplierCode, PurchaseOrderStatus status,
                          BigDecimal paidRatio, String paymentMethod) {}
}
