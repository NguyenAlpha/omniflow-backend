package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.inventory.InventoryBulkAdjustRequest;
import com.quiktech.pos.dto.response.inventory.InventoryTransactionResponse;
import com.quiktech.pos.entity.Business;
import com.quiktech.pos.entity.Inventory;
import com.quiktech.pos.entity.Product;
import com.quiktech.pos.entity.Store;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.entity.Warehouse;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.InventoryRepository;
import com.quiktech.pos.repository.InventoryTransactionRepository;
import com.quiktech.pos.repository.ProductRepository;
import com.quiktech.pos.repository.StoreRepository;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.repository.WarehouseRepository;
import com.quiktech.pos.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock private InventoryRepository inventoryRepository;
    @Mock private InventoryTransactionRepository inventoryTransactionRepository;
    @Mock private StoreRepository storeRepository;
    @Mock private ProductRepository productRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private InventoryService inventoryService;

    private final UserPrincipal manager = new UserPrincipal(2L, "manager", List.of());
    private Store store;
    private Warehouse warehouse;
    private Product cola;
    private Product chips;

    @BeforeEach
    void setUp() {
        store = Store.builder().id(10L).business(Business.builder().id(1L).build()).build();
        warehouse = Warehouse.builder().id(30L).publicId(UUID.randomUUID()).name("Main").build();
        cola = Product.builder().id(101L).publicId(UUID.randomUUID()).sku("SKU-1").name("Cola").build();
        chips = Product.builder().id(102L).publicId(UUID.randomUUID()).sku("SKU-2").name("Chips").build();

        lenient().when(storeRepository.findById(10L)).thenReturn(Optional.of(store));
        lenient().when(warehouseRepository.findByPublicIdAndStoreId(warehouse.getPublicId(), 10L)).thenReturn(Optional.of(warehouse));
        lenient().when(userRepository.getReferenceById(2L)).thenReturn(User.builder().id(2L).username("manager").build());
        givenProduct(cola, 10);
        givenProduct(chips, 5);
    }

    private void givenProduct(Product product, int stock) {
        lenient().when(productRepository.findByBusinessIdAndPublicId(1L, product.getPublicId())).thenReturn(Optional.of(product));
        lenient().when(inventoryRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId()))
                .thenReturn(Optional.of(Inventory.builder().product(product).warehouse(warehouse).store(store)
                        .quantity(BigDecimal.valueOf(stock)).build()));
    }

    private InventoryBulkAdjustRequest request(InventoryBulkAdjustRequest.Item... items) {
        return new InventoryBulkAdjustRequest(warehouse.getPublicId(), List.of(items), "Stock count");
    }

    private InventoryBulkAdjustRequest.Item item(Product product, int delta) {
        return new InventoryBulkAdjustRequest.Item(product.getPublicId(), BigDecimal.valueOf(delta));
    }

    @Test
    void bulkAdjust_appliesEveryRowWithSharedNote() {
        List<InventoryTransactionResponse> result = inventoryService.bulkAdjust(10L, request(item(cola, 20), item(chips, -3)), manager);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).quantity()).isEqualByComparingTo("20");
        assertThat(result.get(0).previousQuantity()).isEqualByComparingTo("10");
        assertThat(result.get(1).quantity()).isEqualByComparingTo("-3");
        assertThat(result.get(1).previousQuantity()).isEqualByComparingTo("5");
        assertThat(result).allSatisfy(tx -> assertThat(tx.note()).isEqualTo("Stock count"));
        verify(productRepository).recalculateTotalStock(101L);
        verify(productRepository).recalculateTotalStock(102L);
    }

    @Test
    void bulkAdjust_negativeStock_namesTheProduct() {
        assertThatThrownBy(() -> inventoryService.bulkAdjust(10L, request(item(cola, 1), item(chips, -6)), manager))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chips (SKU-2)");
    }

    @Test
    void bulkAdjust_duplicateProduct_rejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> inventoryService.bulkAdjust(10L, request(item(cola, 1), item(cola, 2)), manager))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate product");
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void bulkAdjust_zeroQuantity_rejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> inventoryService.bulkAdjust(10L, request(item(cola, 5), item(chips, 0)), manager))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be zero");
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void bulkAdjust_productOutsideBusiness_notFound() {
        Product foreign = Product.builder().id(999L).publicId(UUID.randomUUID()).build();
        when(productRepository.findByBusinessIdAndPublicId(1L, foreign.getPublicId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.bulkAdjust(10L, request(item(foreign, 1)), manager))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
