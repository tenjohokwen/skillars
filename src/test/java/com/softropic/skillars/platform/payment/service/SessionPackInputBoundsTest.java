package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** S-10: out-of-range pack tiers and malformed payment method ids are a 400, not a DB 500 / persisted junk. */
@ExtendWith(MockitoExtension.class)
class SessionPackInputBoundsTest {

    @Mock SessionPackTierRepository sessionPackTierRepository;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock ConfigService configService;

    @InjectMocks SessionPackPaymentService service;

    private void assertTierRejected(int sessionCount, String totalPrice) {
        assertThatThrownBy(() -> service.createTier(UUID.randomUUID(), "x", sessionCount, new BigDecimal(totalPrice)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("payment.invalidPackTier");
        verify(sessionPackTierRepository, never()).save(any());
        verify(sessionPackTierRepository, never()).findAllByCoachIdAndIsActiveTrue(any());
    }

    @Test
    void createTier_sessionCountOverCap_rejected() {
        assertTierRejected(101, "100.00");
    }

    @Test
    void createTier_priceOverColumnCapacity_rejected() {
        assertTierRejected(5, "99999999.99");
    }

    @Test
    void createTier_perSessionShareRoundsToZero_rejectedInsteadOfCheckViolation() {
        assertTierRejected(100, "0.01");
    }

    @Test
    void createTier_subCentPrice_rejected() {
        assertTierRejected(5, "10.005");
    }

    @Test
    void purchasePack_malformedPaymentMethodId_rejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.purchasePack(1L, UUID.randomUUID(), 2L, "not-a-pm", null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("payment.invalidPaymentMethodId");
        verifyNoInteractions(playerProfileRepository, paymentGateway, stripeCustomerRepository);
    }
}
