package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.*;
import com.quiktech.pos.entity.enums.InventoryTransactionType;
import com.quiktech.pos.entity.enums.PurchaseOrderStatus;
import com.quiktech.pos.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int BUSINESS_ONE_EXTRA_ORDERS = 60;
    private static final int MAX_FULFILMENT_DAYS = 10;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StoreRepository storeRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final PaymentRepository paymentRepository;
    private final EntityManager entityManager;

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

    // Dùng chung một ngày gốc cho toàn bộ lịch sử trong lần seed này.
    void seedAt(LocalDate seedDate) {
        LocalDate from = seedDate.minusMonths(FROM_MONTHS_AGO);
        LocalDate to = seedDate.minusMonths(TO_MONTHS_AGO);
        long availableDays = ChronoUnit.DAYS.between(from, to) - 2;
        if (ChronoUnit.DAYS.between(from, to) < MAX_FULFILMENT_DAYS) {
            throw new IllegalStateException("Purchase order seed window must allow at least ten days for receiving and payment");
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
        seedStore("Business One", "Store One", "user1", samples, from, availableDays, true, affectedProducts, summary);
        seedStore("Business Two", "Store Two", "user2", samples, from, availableDays, false, affectedProducts, summary);

        // Flush inventory trước native query để totalStock đọc đúng số lượng vừa cộng.
        if (!affectedProducts.isEmpty()) {
            inventoryRepository.flush();
            affectedProducts.forEach(productRepository::recalculateTotalStock);
        }
        purchaseOrderRepository.flush();
        summary.logAfterCommit(log, "Purchase order", "from=" + from + ", to=" + to);
    }

    private void seedStore(String businessName, String storeName, String username, List<Sample> samples,
                           LocalDate from, long availableDays, boolean diversified,
                           Set<Long> affectedProducts, SeedSummary summary) {
        Store store = storeRepository.findByNameAndDeletedAtIsNull(storeName)
                .orElseThrow(() -> new IllegalStateException("Store not found: " + storeName + " — run StoreSeeder first"));
        if (!businessName.equals(store.getBusiness().getName())) {
            throw new IllegalStateException("Seed store does not belong to " + businessName + ": " + storeName);
        }

        Warehouse warehouse = warehouseRepository.findByStoreIdAndNameAndDeletedAtIsNull(store.getId(), "Kho chính")
                .orElseThrow(() -> new IllegalStateException("Warehouse not found for " + storeName + " — run WarehouseSeeder first"));
        if (!Boolean.TRUE.equals(warehouse.getIsActive())) {
            throw new IllegalStateException("Seed warehouse is inactive: " + storeName);
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found: " + username + " — run UserSeeder first"));
        List<Product> products = productRepository.findAllByBusinessIdAndIsActive(store.getBusiness().getId(), true)
                .stream().sorted(Comparator.comparing(Product::getSku)).toList();
        List<PlannedOrder> plans = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            Instant createdAt = from.plusDays(availableDays * i / (samples.size() - 1))
                    .atTime(9 + i, 15).atZone(SEED_ZONE).toInstant();
            Instant eventAt = switch (sample.status()) {
                case PENDING -> createdAt;
                case CANCELLED -> createdAt.plus(1, ChronoUnit.DAYS);
                case RECEIVED -> createdAt.plus(2, ChronoUnit.DAYS);
            };
            List<ItemSpec> lines = new ArrayList<>();
            for (String sku : List.of("SP001", "SP002")) {
                Product product = products.stream().filter(p -> sku.equals(p.getSku())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Product not found: " + businessName + "/" + sku + " — run ProductSeeder first"));
                lines.add(new ItemSpec(product, BigDecimal.valueOf((i + 1L) * (lines.isEmpty() ? 10 : 5)), product.getCostPrice()));
            }
            plans.add(new PlannedOrder(sample, createdAt, eventAt, eventAt, lines,
                    "Đơn nhập hàng mẫu ban đầu", false));
        }
        if (diversified) {
            plans.addAll(diverseOrders(products, from, availableDays + 2 - MAX_FULFILMENT_DAYS));
        }

        // Nhập theo ngày nhận để previousQuantity nối tiếp đúng theo lịch sử.
        plans.sort(Comparator.comparing(PlannedOrder::receivedAt).thenComparing(p -> p.sample().code()));
        for (PlannedOrder plan : plans) {
            Sample sample = plan.sample();
            // Check trước mọi side effect; giữ nguyên ngày và số tiền của đơn đã tồn tại.
            if (purchaseOrderRepository.findByStoreIdAndOrderCode(store.getId(), sample.code()).isPresent()) {
                summary.record(false);
                continue;
            }

            Supplier supplier = supplierRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(store.getBusiness().getId(), sample.supplierCode())
                    .orElseThrow(() -> new IllegalStateException("Supplier not found: " + businessName + "/" + sample.supplierCode() + " — run SupplierSeeder first"));

            seedOrder(store, warehouse, supplier, user, plan, affectedProducts);
            summary.record(true);
        }
    }

    private List<PlannedOrder> diverseOrders(List<Product> products, LocalDate from, long creationDays) {
        if (products.size() < 10) {
            throw new IllegalStateException("Business One needs at least ten active products — run ProductSeeder first");
        }
        String[] notes = {
                "Bổ sung hàng bán chạy đầu tuần", "Nhập hàng chuẩn bị chương trình khuyến mãi",
                "Bổ sung các mặt hàng gần hết tồn", "Nhập lô hàng theo báo giá nhà cung cấp",
                "Nhập hàng dự trữ cho cuối tuần", "Bổ sung hàng theo nhu cầu khách hàng"
        };
        String[] methods = {"BANK_TRANSFER", "CASH", "BANK_TRANSFER", "MOBILE_PAYMENT", "BANK_TRANSFER"};
        int[] quantities = {6, 12, 24, 36, 48, 60, 96, 120, 180};
        List<PlannedOrder> plans = new ArrayList<>();
        int receivedProductCursor = 0;
        for (int i = 0; i < BUSINESS_ONE_EXTRA_ORDERS; i++) {
            int scenario = i % 10;
            PurchaseOrderStatus status = switch (scenario) {
                case 0 -> PurchaseOrderStatus.PENDING;
                case 1 -> PurchaseOrderStatus.CANCELLED;
                default -> PurchaseOrderStatus.RECEIVED;
            };
            BigDecimal ratio = switch (scenario) {
                case 3 -> new BigDecimal("0.25");
                case 4 -> new BigDecimal("0.60");
                case 7 -> new BigDecimal("0.50");
                case 5, 6, 8 -> BigDecimal.ONE;
                default -> BigDecimal.ZERO;
            };
            boolean instalments = scenario == 6 || scenario == 7;
            Sample sample = new Sample("PO-SEED-%03d".formatted(i + 6), "SUP-%03d".formatted(i % 3 + 1),
                    status, ratio, methods[(i / 3) % methods.length]);
            Instant createdAt = from.plusDays(creationDays * i / (BUSINESS_ONE_EXTRA_ORDERS - 1))
                    .atTime(8 + (i * 7) % 10, (i * 17) % 60).atZone(SEED_ZONE).toInstant();
            Instant receivedAt = status == PurchaseOrderStatus.PENDING ? createdAt
                    : createdAt.plus(status == PurchaseOrderStatus.CANCELLED ? 1 : 1 + i % 3, ChronoUnit.DAYS);
            Instant updatedAt = instalments ? receivedAt.plus(3 + i % 5, ChronoUnit.DAYS) : receivedAt;
            int lineCount = 3 + (i * 7) % 8;
            int startProduct = status == PurchaseOrderStatus.RECEIVED ? receivedProductCursor : i * 11;
            List<ItemSpec> lines = new ArrayList<>();
            for (int j = 0; j < lineCount; j++) {
                Product product = products.get((startProduct + j) % products.size());
                BigDecimal quantity = BigDecimal.valueOf(quantities[(i * 3 + j * 2) % quantities.length]);
                // Giá từng lô dao động quanh giá vốn, không thay đổi giá danh mục.
                BigDecimal factor = BigDecimal.valueOf(94 + (i + j * 3) % 11, 2);
                BigDecimal unitPrice = product.getCostPrice().multiply(factor).setScale(2, RoundingMode.HALF_UP);
                lines.add(new ItemSpec(product, quantity, unitPrice));
            }
            if (status == PurchaseOrderStatus.RECEIVED) receivedProductCursor += lineCount;
            String note = switch (status) {
                case PENDING -> "Đang chờ xác nhận lịch giao hàng - " + notes[i % notes.length];
                case CANCELLED -> "Hủy do nhà cung cấp không đủ hàng - " + notes[i % notes.length];
                case RECEIVED -> notes[i % notes.length] + (instalments ? " - thanh toán hai đợt" : "");
            };
            plans.add(new PlannedOrder(sample, createdAt, receivedAt, updatedAt, lines, note, instalments));
        }
        return plans;
    }

    private void seedOrder(Store store, Warehouse warehouse, Supplier supplier, User user, PlannedOrder plan,
                           Set<Long> affectedProducts) {
        Sample sample = plan.sample();
        PurchaseOrder po = PurchaseOrder.builder()
                .store(store).warehouse(warehouse).supplier(supplier)
                .orderCode(sample.code()).status(sample.status()).paymentMethod(sample.paymentMethod())
                .note(plan.note())
                .publicId(UUID.randomUUID()).createdBy(user).lastModifiedByUser(user)
                .createdAt(plan.createdAt()).updatedAt(plan.updatedAt()).lastModifiedAt(plan.updatedAt())
                .build();

        List<PurchaseOrderItem> items = new ArrayList<>();
        for (ItemSpec line : plan.lines()) {
            items.add(PurchaseOrderItem.builder()
                    .purchaseOrder(po).store(store).product(line.product())
                    .quantity(line.quantity()).unitPrice(line.unitPrice())
                    .totalPrice(line.unitPrice().multiply(line.quantity()))
                    .publicId(UUID.randomUUID()).lastModifiedByUser(user).lastModifiedAt(plan.createdAt())
                    .build());
        }
        BigDecimal total = items.stream().map(PurchaseOrderItem::getTotalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = total.multiply(sample.paidRatio()).setScale(2, RoundingMode.HALF_UP);
        po.setPurchaseOrderItems(items);
        po.setTotalAmount(total);
        po.setPaidAmount(paid);
        po.setDebtAmount(total.subtract(paid));
        // Đây chắc chắn là đơn mới (đã kiểm tra mã trước đó). Persist cả graph trực tiếp:
        // tránh merge gây UPDATE phụ và @PreUpdate ghi đè ngày lịch sử bằng now().
        entityManager.persist(po);

        if (sample.status() != PurchaseOrderStatus.RECEIVED) return;

        for (PurchaseOrderItem item : po.getPurchaseOrderItems()) {
            receiveItem(po, item, user, plan.receivedAt());
            affectedProducts.add(item.getProduct().getId());
        }
        if (po.getDebtAmount().signum() > 0) {
            supplier.setDebtBalance(supplier.getDebtBalance().add(po.getDebtAmount()));
            supplier.setLastModifiedByUser(user);
            supplier.setLastModifiedAt(Instant.now());
            supplierRepository.save(supplier);
        }
        if (paid.signum() > 0) {
            if (plan.instalments()) {
                BigDecimal firstPayment = paid.multiply(new BigDecimal("0.40")).setScale(2, RoundingMode.HALF_UP);
                seedPayment(po, user, firstPayment, plan.receivedAt());
                seedPayment(po, user, paid.subtract(firstPayment), plan.updatedAt());
            } else {
                seedPayment(po, user, paid, plan.receivedAt());
            }
        }
    }

    private void seedPayment(PurchaseOrder po, User user, BigDecimal amount, Instant paidAt) {
        paymentRepository.save(Payment.builder()
                .store(po.getStore()).supplier(po.getSupplier()).amount(amount).paymentMethod(po.getPaymentMethod())
                .note("Purchase order: " + po.getOrderCode())
                .publicId(UUID.randomUUID()).createdBy(user).lastModifiedByUser(user)
                .createdAt(paidAt).updatedAt(paidAt).lastModifiedAt(paidAt)
                .build());
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

    private record ItemSpec(Product product, BigDecimal quantity, BigDecimal unitPrice) {}

    private record PlannedOrder(Sample sample, Instant createdAt, Instant receivedAt, Instant updatedAt,
                                List<ItemSpec> lines, String note, boolean instalments) {}
}
