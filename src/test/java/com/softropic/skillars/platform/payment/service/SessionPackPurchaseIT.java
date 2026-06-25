package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for session pack purchases.
 * AC 5: pricePerSession is locked at purchase time from the tier's rate.
 */
class SessionPackPurchaseIT extends BasePaymentIT {

    @Autowired SessionPackPaymentService sessionPackPaymentService;
    @Autowired SessionPackTierRepository sessionPackTierRepository;

    private UUID coachId;
    private static final long PARENT_ID = 77001L;

    @BeforeEach
    void setUpCoach() {
        coachId = insertTestCoach(77002L, "purchase_coach@test.com", "Purchase Coach");
    }

    @Test
    void purchasePack_pricePerSessionLockedFromTierRate() {
        // Create tier: 10 sessions @ €300 total → €30/session
        UUID tierId = sessionPackPaymentService
            .createTier(coachId, "10-Pack €30/session", 10, new BigDecimal("300.00"))
            .packTierId();

        SessionPackPurchaseResponse purchase =
            sessionPackPaymentService.purchasePack(PARENT_ID, tierId, "pm_test_pi");

        assertThat(purchase.pricePerSession())
            .as("pricePerSession must be locked at tier's rate")
            .isEqualByComparingTo("30.00");
        assertThat(purchase.remainingSessions()).isEqualTo(10);
        assertThat(purchase.stripePaymentIntentId()).isNotBlank();
    }

    @Test
    void purchasePack_tierPriceChangedAfterPurchase_existingPurchaseUnaffected() {
        // Original tier: 5 sessions @ €100 → €20/session
        UUID originalTierId = sessionPackPaymentService
            .createTier(coachId, "5-Pack Original", 5, new BigDecimal("100.00"))
            .packTierId();

        SessionPackPurchaseResponse firstPurchase =
            sessionPackPaymentService.purchasePack(PARENT_ID, originalTierId, null);
        assertThat(firstPurchase.pricePerSession()).isEqualByComparingTo("20.00");

        // Coach reprices: new tier (old tier is deactivated) — new price is €30/session
        sessionPackPaymentService.createTier(coachId, "5-Pack Repriced", 5, new BigDecimal("150.00"));

        // Query the original purchase directly — pricePerSession must remain €20
        BigDecimal lockedPrice = jdbcTemplate.queryForObject(
            "SELECT price_per_session FROM payment.session_pack_purchases WHERE purchase_id = ?",
            BigDecimal.class, firstPurchase.purchaseId()
        );
        assertThat(lockedPrice).isEqualByComparingTo("20.00");
    }

    @Test
    void purchasePack_inactiveTier_rejected() {
        UUID tierId = sessionPackPaymentService
            .createTier(coachId, "Active Tier", 5, new BigDecimal("100.00"))
            .packTierId();

        // Deactivate the tier
        sessionPackPaymentService.deactivateTier(coachId, tierId);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> sessionPackPaymentService.purchasePack(PARENT_ID, tierId, null))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.packTierInactive");
    }
}
