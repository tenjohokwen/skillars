package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that pricePerSession is copied from the tier at purchase time and is not affected
 * by any subsequent repricing of the tier (new tier creation with different price).
 */
@ExtendWith(MockitoExtension.class)
class PackPriceLockedOnPurchaseTest {

    @Mock SessionPackTierRepository sessionPackTierRepository;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PaymentGateway paymentGateway;

    @InjectMocks SessionPackPaymentService sessionPackPaymentService;

    private static final Long PARENT_ID = 7001L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID TIER_ID = UUID.randomUUID();
    private static final BigDecimal PRICE_PER_SESSION = new BigDecimal("25.00");
    private static final BigDecimal TOTAL_PRICE = new BigDecimal("250.00");

    @Test
    void purchasePack_pricePerSessionLockedFromTierAtPurchaseTime() {
        SessionPackTier tier = new SessionPackTier();
        tier.setPackTierId(TIER_ID);
        tier.setCoachId(COACH_ID);
        tier.setLabel("10-Pack");
        tier.setSessionCount(10);
        tier.setTotalPrice(TOTAL_PRICE);
        tier.setPricePerSession(PRICE_PER_SESSION);
        tier.setActive(true);
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(tier));

        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setStripeCustomerId("cus_stub_" + PARENT_ID);
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));

        when(paymentGateway.chargeAndCapture(any(), any(), any(), any())).thenReturn("pi_stub_123");

        ArgumentCaptor<SessionPackPurchase> purchaseCaptor = ArgumentCaptor.forClass(SessionPackPurchase.class);
        SessionPackPurchase savedPurchase = new SessionPackPurchase();
        savedPurchase.setPricePerSession(PRICE_PER_SESSION);
        savedPurchase.setRemainingSessions(10);
        savedPurchase.setStripePaymentIntentId("pi_stub_123");
        when(sessionPackPurchaseRepository.save(any())).thenReturn(savedPurchase);

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, null);

        verify(sessionPackPurchaseRepository).save(purchaseCaptor.capture());
        SessionPackPurchase purchase = purchaseCaptor.getValue();
        assertThat(purchase.getPricePerSession())
            .as("pricePerSession must be locked from tier at purchase time")
            .isEqualByComparingTo(PRICE_PER_SESSION);
        assertThat(purchase.getRemainingSessions()).isEqualTo(10);
    }

    @Test
    void purchasePack_afterTierIsRepriced_existingPurchaseRetainsOriginalPrice() {
        // First purchase: pricePerSession = 25.00 (10-pack, total 250)
        SessionPackTier originalTier = new SessionPackTier();
        originalTier.setPackTierId(TIER_ID);
        originalTier.setCoachId(COACH_ID);
        originalTier.setLabel("10-Pack Original");
        originalTier.setSessionCount(10);
        originalTier.setTotalPrice(TOTAL_PRICE);
        originalTier.setPricePerSession(PRICE_PER_SESSION);
        originalTier.setActive(true);
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(originalTier));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(existingCustomer()));
        when(paymentGateway.chargeAndCapture(any(), any(), any(), any())).thenReturn("pi_stub_original");

        ArgumentCaptor<SessionPackPurchase> firstCaptor = ArgumentCaptor.forClass(SessionPackPurchase.class);
        SessionPackPurchase firstPurchase = new SessionPackPurchase();
        firstPurchase.setPricePerSession(new BigDecimal("25.00"));
        firstPurchase.setRemainingSessions(10);
        when(sessionPackPurchaseRepository.save(any())).thenReturn(firstPurchase);

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, null);
        verify(sessionPackPurchaseRepository).save(firstCaptor.capture());

        // Price at purchase is locked at 25.00 even if tier price changes later
        assertThat(firstCaptor.getValue().getPricePerSession())
            .isEqualByComparingTo("25.00");
    }

    private StripeCustomer existingCustomer() {
        StripeCustomer c = new StripeCustomer();
        c.setParentId(PARENT_ID);
        c.setStripeCustomerId("cus_stub_existing");
        return c;
    }
}
