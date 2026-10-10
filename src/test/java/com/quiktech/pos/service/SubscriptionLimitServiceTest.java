package com.quiktech.pos.service;

import com.quiktech.pos.dto.response.catalog.ProductLimitResponse;
import com.quiktech.pos.entity.Subscription;
import com.quiktech.pos.exception.ResourceNotFoundException;
import com.quiktech.pos.repository.ProductRepository;
import com.quiktech.pos.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionLimitServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private ProductRepository productRepository;

    @InjectMocks private SubscriptionLimitService subscriptionLimitService;

    private void givenPlan(Integer maxProducts, long currentProducts) {
        when(subscriptionRepository.findByBusinessId(1L))
                .thenReturn(Optional.of(Subscription.builder().maxProducts(maxProducts).build()));
        when(productRepository.countByBusinessIdAndDeletedAtIsNull(1L)).thenReturn(currentProducts);
    }

    @Test
    void getProductLimit_belowMax_canCreate() {
        givenPlan(50, 49);
        assertThat(subscriptionLimitService.getProductLimit(1L))
                .isEqualTo(new ProductLimitResponse(true, 49, 50));
    }

    @Test
    void getProductLimit_atMax_cannotCreate() {
        givenPlan(50, 50);
        assertThat(subscriptionLimitService.getProductLimit(1L))
                .isEqualTo(new ProductLimitResponse(false, 50, 50));
    }

    @Test
    void getProductLimit_unlimitedPlan_canCreate() {
        givenPlan(null, 10_000);
        assertThat(subscriptionLimitService.getProductLimit(1L))
                .isEqualTo(new ProductLimitResponse(true, 10_000, null));
    }

    @Test
    void getProductLimit_noSubscription_throws() {
        when(subscriptionRepository.findByBusinessId(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> subscriptionLimitService.getProductLimit(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
