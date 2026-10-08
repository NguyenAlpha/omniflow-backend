package com.quiktech.pos.config.seed;

import com.quiktech.pos.entity.*;
import com.quiktech.pos.entity.enums.DiscountType;
import com.quiktech.pos.entity.enums.InventoryTransactionType;
import com.quiktech.pos.entity.enums.OrderStatus;
import com.quiktech.pos.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
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
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@Component
@org.springframework.core.annotation.Order(7)
@RequiredArgsConstructor
@Slf4j
public class OrderSeeder implements ApplicationRunner {

    private static final ZoneId SEED_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    // Chỉnh lịch sử tại đây; mọi sự kiện đều không vượt quá mốc cuối.
    private static final int FROM_MONTHS_AGO = 3;
    private static final int TO_MONTHS_AGO = 0;
    private static final int ORDER_COUNT = 150;
    private static final String[] PAYMENT_METHODS = {"CASH", "BANK_TRANSFER", "MOBILE_PAYMENT"};
    private static final String[] NOTES = {
            "Mua bổ sung đồ dùng gia đình", "Mua hàng cho cuối tuần", "Khách mua tại cửa hàng",
            "Mua thực phẩm và đồ uống", "Mua hàng cho văn phòng", "Khách quen ghé mua hàng"
    };

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final StoreRepository storeRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final EntityManager entityManager;

    @Value("#{${seed.enabled:false} and ${order.seed.enabled:false}}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("Order seeder is disabled");
            return;
        }
        if (FROM_MONTHS_AGO <= TO_MONTHS_AGO || TO_MONTHS_AGO < 0) {
            throw new IllegalStateException("Order seed requires FROM_MONTHS_AGO > TO_MONTHS_AGO >= 0");
        }
        Instant seedTime = Instant.now();
        LocalDate seedDate = seedTime.atZone(SEED_ZONE).toLocalDate();
        LocalDate fromDate = seedDate.minusMonths(FROM_MONTHS_AGO);
        Instant end = seedTime.atZone(SEED_ZONE).minusMonths(TO_MONTHS_AGO).toInstant();
        SeedSummary summary = new SeedSummary();
        Store store = storeRepository.findByNameAndDeletedAtIsNull("Store One")
                .orElseThrow(() -> new IllegalStateException("Store One not found — run StoreSeeder first"));
        if (!"Business One".equals(store.getBusiness().getName())) {
            throw new IllegalStateException("Store One must belong to Business One");
        }

        // Kiểm tra mã trước mọi side effect, kể cả cộng nợ và trừ tồn kho.
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < ORDER_COUNT; i++) {
            if (orderRepository.findByStoreIdAndOrderCode(store.getId(), code(i)).isPresent()) {
                summary.record(false);
            } else {
                missing.add(i);
            }
        }
        if (missing.isEmpty()) {
            summary.logAfterCommit(log, "Order");
            return;
        }

        Warehouse warehouse = warehouseRepository.findByStoreIdAndNameAndDeletedAtIsNull(store.getId(), "Kho chính")
                .orElseThrow(() -> new IllegalStateException("Kho chính not found — run WarehouseSeeder first"));
        if (!Boolean.TRUE.equals(warehouse.getIsActive())) {
            throw new IllegalStateException("Seed warehouse is inactive");
        }
        User user = userRepository.findByUsername("user1")
                .orElseThrow(() -> new IllegalStateException("user1 not found — run UserSeeder first"));
        List<Customer> customers = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            String customerCode = "CUS-SEED-%03d".formatted(i);
            customers.add(customerRepository.findByBusinessIdAndCodeAndDeletedAtIsNull(store.getBusiness().getId(), customerCode)
                    .orElseThrow(() -> new IllegalStateException("Customer not found: " + customerCode + " — run CustomerSeeder first")));
        }
        List<Product> products = productRepository.findAllByBusinessIdAndIsActive(store.getBusiness().getId(), true)
                .stream().sorted(Comparator.comparing(Product::getSku)).toList();
        if (products.size() < 8) {
            throw new IllegalStateException("Order seed needs at least eight active products — run ProductSeeder first");
        }

        List<Plan> plans = missing.stream().map(i -> plan(i, customers, fromDate, end))
                .sorted(Comparator.comparing(Plan::createdAt).thenComparingInt(Plan::index)).toList();
        // Không chèn lùi vào lịch sử kho có sẵn: previousQuantity phải nối tiếp đúng.
        Instant latestStockEvent = entityManager.createQuery(
                        "select max(t.createdAt) from InventoryTransaction t where t.warehouse.id = :warehouseId", Instant.class)
                .setParameter("warehouseId", warehouse.getId()).getSingleResult();
        if (latestStockEvent != null && !latestStockEvent.isBefore(plans.getFirst().createdAt())) {
            throw new IllegalStateException("Order seed overlaps existing inventory history; use a later seed window or a fresh demo database");
        }

        List<StockEvent> stockEvents = new ArrayList<>();
        for (Plan plan : plans) {
            Order order = seedOrder(store, warehouse, user, products, plan);
            stockEvents.add(new StockEvent(order, plan.createdAt(), InventoryTransactionType.OUT));
            if (plan.status() == OrderStatus.CANCELLED) {
                stockEvents.add(new StockEvent(order, plan.updatedAt(), InventoryTransactionType.IN));
            }
            summary.record(true);
        }
        stockEvents.sort(Comparator.comparing(StockEvent::at)
                .thenComparing(e -> e.order().getOrderCode())
                .thenComparingInt(e -> e.type() == InventoryTransactionType.OUT ? 0 : 1));
        Set<Long> affectedProducts = new HashSet<>();
        for (StockEvent event : stockEvents) {
            applyStockEvent(event, user, affectedProducts);
        }
        entityManager.flush();
        affectedProducts.forEach(productRepository::recalculateTotalStock);
        summary.logAfterCommit(log, "Order");
    }

    private Plan plan(int i, List<Customer> customers, LocalDate fromDate, Instant end) {
        int scenario = i % 15;
        boolean walkIn = scenario < 6;
        // Mỗi 15 đơn: 6 vãng lai, 4 trả đủ, 2 trả một phần, 1 chưa trả, 2 chưa hoàn tất.
        // Hai chu kỳ đầu dùng thêm một đơn chờ: tổng 130 COMPLETED / 12 PENDING / 8 CANCELLED.
        OrderStatus status = scenario == 13 || (scenario == 14 && i / 15 < 2) ? OrderStatus.PENDING
                : scenario == 14 ? OrderStatus.CANCELLED : OrderStatus.COMPLETED;
        int customerVisit = i / 15 * 9 + scenario - 6;
        Customer customer = walkIn ? null : customers.get(customerVisit < 30 ? customerVisit : (customerVisit - 30) % 24);
        Random random = new Random(20261008L + i * 7919L);
        LocalDate start = status == OrderStatus.PENDING ? end.atZone(SEED_ZONE).toLocalDate().minusDays(6) : fromDate;
        if (start.isBefore(fromDate)) start = fromDate;
        long days = ChronoUnit.DAYS.between(start, end.atZone(SEED_ZONE).toLocalDate());
        Instant createdAt = start.plusDays(random.nextLong(days + 1))
                .atTime(8 + random.nextInt(13), random.nextInt(60), random.nextInt(60)).atZone(SEED_ZONE).toInstant();
        createdAt = earlier(createdAt, end);
        if (customer != null && customer.getCreatedAt().isAfter(createdAt)) {
            throw new IllegalStateException("Customer " + customer.getCode() + " was created after planned order " + code(i));
        }
        Instant completedAt = earlier(createdAt.plus(5 + random.nextInt(55), ChronoUnit.MINUTES), end);
        boolean instalments = !walkIn && status == OrderStatus.COMPLETED && scenario < 12 && i % 2 == 0;
        Instant updatedAt = status == OrderStatus.PENDING ? createdAt
                : instalments ? earlier(completedAt.plus(2 + random.nextInt(8), ChronoUnit.DAYS), end) : completedAt;
        instalments = instalments && updatedAt.isAfter(completedAt);
        BigDecimal paidRatio = status != OrderStatus.COMPLETED || scenario == 12 ? BigDecimal.ZERO
                : scenario == 10 ? new BigDecimal("0.40") : scenario == 11 ? new BigDecimal("0.70") : BigDecimal.ONE;
        return new Plan(i, customer, status, paidRatio, instalments, createdAt, completedAt, updatedAt);
    }

    private Order seedOrder(Store store, Warehouse warehouse, User user, List<Product> products, Plan plan) {
        int i = plan.index();
        String note = switch (plan.status()) {
            case PENDING -> "Chờ khách xác nhận - " + NOTES[i % NOTES.length];
            case CANCELLED -> "Khách đổi nhu cầu, hủy trước thanh toán";
            case COMPLETED -> NOTES[i % NOTES.length] + (plan.instalments() ? " - thanh toán hai đợt" : "");
        };
        Order order = Order.builder()
                .store(store).warehouse(warehouse).customer(plan.customer()).orderCode(code(i)).status(plan.status())
                .paymentMethod(PAYMENT_METHODS[(i / 2) % PAYMENT_METHODS.length]).note(note)
                .publicId(UUID.randomUUID()).createdBy(user).lastModifiedByUser(user)
                .createdAt(plan.createdAt()).updatedAt(plan.updatedAt()).lastModifiedAt(plan.updatedAt()).build();
        List<OrderItem> items = new ArrayList<>();
        for (int j = 0; j < 1 + (i * 5) % 8; j++) {
            Product product = products.get((i * 7 + j) % products.size());
            if (product.getCreatedAt().isAfter(plan.createdAt())) {
                throw new IllegalStateException("Product " + product.getSku() + " was created after " + code(i));
            }
            BigDecimal quantity = BigDecimal.valueOf(1 + (i + j * 3) % 5);
            items.add(OrderItem.builder().order(order).store(store).product(product)
                    .quantity(quantity).unitPrice(product.getSellingPrice())
                    .totalPrice(money(product.getSellingPrice().multiply(quantity)))
                    .publicId(UUID.randomUUID()).lastModifiedByUser(user).lastModifiedAt(plan.createdAt()).build());
        }
        BigDecimal subtotal = items.stream().map(OrderItem::getTotalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        DiscountType discountType = i % 3 == 2 ? DiscountType.PERCENT : DiscountType.FIXED;
        BigDecimal discount = switch (i % 3) {
            case 1 -> new BigDecimal("1000").min(money(subtotal.multiply(new BigDecimal("0.10"))));
            case 2 -> BigDecimal.valueOf(i % 2 == 0 ? 5 : 10);
            default -> BigDecimal.ZERO;
        };
        BigDecimal discountAmount = discountType == DiscountType.PERCENT
                ? money(subtotal.multiply(discount).movePointLeft(2)) : discount;
        BigDecimal total = money(subtotal.subtract(discountAmount));
        BigDecimal paid = money(total.multiply(plan.paidRatio()));
        order.setOrderItems(items);
        order.setSubtotal(subtotal);
        order.setDiscount(discount);
        order.setDiscountType(discountType);
        order.setTotalAmount(total);
        order.setPaidAmount(paid);
        order.setDebtAmount(total.subtract(paid));
        // Persist graph mới ở trạng thái cuối, tránh @PreUpdate ghi đè audit lịch sử.
        entityManager.persist(order);

        if (plan.status() == OrderStatus.COMPLETED) {
            if (order.getCustomer() != null && order.getDebtAmount().signum() > 0) {
                Customer customer = order.getCustomer();
                customer.setDebtBalance(customer.getDebtBalance().add(order.getDebtAmount()));
                customer.setLastModifiedByUser(user);
                customer.setLastModifiedAt(Instant.now());
            }
            if (paid.signum() > 0) {
                if (plan.instalments()) {
                    BigDecimal first = money(paid.multiply(new BigDecimal("0.40")));
                    seedPayment(order, user, first, plan.completedAt());
                    seedPayment(order, user, paid.subtract(first), plan.updatedAt());
                } else {
                    seedPayment(order, user, paid, plan.completedAt());
                }
            }
        }
        return order;
    }

    private void seedPayment(Order order, User user, BigDecimal amount, Instant at) {
        entityManager.persist(Payment.builder()
                .store(order.getStore()).customer(order.getCustomer()).amount(amount).paymentMethod(order.getPaymentMethod())
                .note("Order: " + order.getOrderCode()).publicId(UUID.randomUUID())
                .createdBy(user).lastModifiedByUser(user).createdAt(at).updatedAt(at).lastModifiedAt(at).build());
    }

    private void applyStockEvent(StockEvent event, User user, Set<Long> affectedProducts) {
        Order order = event.order();
        for (OrderItem item : order.getOrderItems()) {
            Inventory inventory = inventoryRepository.findByProductIdAndWarehouseId(item.getProduct().getId(), order.getWarehouse().getId())
                    .orElseThrow(() -> new IllegalStateException("Stock missing for " + item.getProduct().getSku() + " — run PurchaseOrderSeeder first"));
            BigDecimal previous = inventory.getQuantity();
            boolean outbound = event.type() == InventoryTransactionType.OUT;
            BigDecimal next = outbound ? previous.subtract(item.getQuantity()) : previous.add(item.getQuantity());
            if (next.signum() < 0) {
                throw new IllegalStateException("Insufficient stock for " + item.getProduct().getSku() + " in " + order.getOrderCode());
            }
            inventory.setQuantity(next);
            inventory.setLastModifiedByUser(user);
            inventory.setLastModifiedAt(Instant.now());
            // Inventory và Customer lưu số dư hiện tại; ngày lịch sử nằm trên các giao dịch.
            entityManager.persist(InventoryTransaction.builder()
                    .store(order.getStore()).warehouse(order.getWarehouse()).product(item.getProduct()).order(order)
                    .type(event.type()).quantity(item.getQuantity()).previousQuantity(previous)
                    .note((outbound ? "Order: " : "Cancel order: ") + order.getOrderCode()).createdBy(user)
                    .createdAt(event.at()).updatedAt(event.at()).build());
            affectedProducts.add(item.getProduct().getId());
        }
    }

    private static String code(int index) { return "ORD-SEED-%03d".formatted(index + 1); }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }

    private record Plan(int index, Customer customer, OrderStatus status, BigDecimal paidRatio, boolean instalments,
                        Instant createdAt, Instant completedAt, Instant updatedAt) {}
    private record StockEvent(Order order, Instant at, InventoryTransactionType type) {}
}
