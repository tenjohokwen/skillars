package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchase;
import com.softropic.skillars.platform.payment.repo.SessionPackPurchaseRepository;
import com.softropic.skillars.platform.payment.repo.SessionPackTier;
import com.softropic.skillars.platform.payment.repo.SessionPackTierRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfile;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.stripe.model.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionPackPaymentServiceTest {

    @Mock SessionPackTierRepository sessionPackTierRepository;
    @Mock SessionPackPurchaseRepository sessionPackPurchaseRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PlayerProfileRepository playerProfileRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock com.softropic.skillars.platform.config.service.ConfigService configService;
    @Mock StripeClient stripeClient;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock RefundReconciliationService refundReconciliationService;
    @Mock SubscriptionService subscriptionService;
    @Mock StripeCustomerEmailSyncOutboxSupport stripeCustomerEmailSync;
    @Mock StripeCustomerCardWrites stripeCustomerCardWrites;
    @Mock com.softropic.skillars.platform.security.service.AgeVerificationService ageVerificationService;
    @Mock com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttemptRepository attemptRepository;
    @Mock com.softropic.skillars.platform.payment.repo.StripeRefundFailureRepository refundFailureRepository;

    @InjectMocks SessionPackPaymentService sessionPackPaymentService;

    @BeforeEach
    void setUpLockRetryer() {
        // skillars-deferred-169: every purchase inserts an attempt row first; the insert hands back the row with an id.
        lenient().when(attemptRepository.saveAndFlush(any())).thenAnswer(inv -> {
            com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt a = inv.getArgument(0);
            a.setId(1L);
            a.setCreatedAt(Instant.now());
            return a;
        });
        lenient().when(attemptRepository.transition(any(), any(), any(), any())).thenReturn(true);
        // skillars-deferred-174: a named card is the payer's own unless a test says otherwise, and the success-path card
        // re-apply finds the column as the purchase left it.
        lenient().when(paymentGateway.paymentMethodBelongsTo(any(), any())).thenReturn(true);
        lenient().when(subscriptionService.reapplyPaymentMethodAndScheduleSync(any(), any(), any())).thenReturn(true);
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        // fix/payment-pack-integrity: purchasePack checks the tier's coach is bookable.
        com.softropic.skillars.platform.marketplace.repo.CoachProfile coach =
            new com.softropic.skillars.platform.marketplace.repo.CoachProfile();
        coach.setStatus(com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus.ACTIVE);
        lenient().when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
        // skillars-deferred-170 AC3: ... and that the coach can be paid (an unstubbed mock would answer false).
        lenient().when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(true);
    }

    // ── fix/payment-pack-integrity ─────────────────────────────────────────────────────────────

    @Test
    void purchasePack_suspendedCoach_refusedBeforeAnyCharge() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        com.softropic.skillars.platform.marketplace.repo.CoachProfile suspended =
            new com.softropic.skillars.platform.marketplace.repo.CoachProfile();
        suspended.setStatus(com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus.SUSPENDED);
        when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test"))
            .isInstanceOf(OperationNotAllowedException.class);
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any(), any());
        verify(stripeCustomerRepository, never()).save(any());
    }

    // ── skillars-deferred-170 AC3 ──────────────────────────────────────────────────────────────

    /** A first-time payer of a coach with no usable Connect account: refused before the Stripe customer exists. */
    @Test
    void purchasePack_coachNotPaymentReady_firstTimePayer_refusedBeforeAnyWrite() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(false);
        lenient().when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test", UUID.randomUUID().toString()))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.coachStripeNotConfigured");
        verify(paymentGateway, never()).createStripeCustomer(any());
        verify(stripeCustomerRepository, never()).save(any());
        verify(attemptRepository, never()).saveAndFlush(any());
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any(), any());
    }

    /** A returning payer who sent a new card: the saved card is left as it was. */
    @Test
    void purchasePack_coachNotPaymentReady_returningPayerWithNewCard_cardUntouched() {
        StripeCustomer customer = existingCustomerWithCard("pm_old");
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(paymentGateway.isCoachPaymentReady(COACH_ID)).thenReturn(false);
        lenient().when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.coachStripeNotConfigured");
        assertThat(customer.getStripePaymentMethodId()).isEqualTo("pm_old");
        verify(stripeCustomerRepository, never()).save(any());
        verify(subscriptionService, never()).reapplyPaymentMethodAndScheduleSync(any(), any(), any());
        verify(attemptRepository, never()).saveAndFlush(any());
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any(), any());
    }

    private StripeCustomer existingCustomerWithCard(String card) {
        StripeCustomer sc = new StripeCustomer();
        sc.setParentId(PARENT_ID);
        sc.setStripeCustomerId("cus_test");
        sc.setStripePaymentMethodId(card);
        return sc;
    }

    private void stubPurchaseWithExistingCustomer(SessionPackTier tier, String savedCard) {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(tier));
        when(stripeCustomerRepository.findById(PARENT_ID))
            .thenAnswer(inv -> Optional.of(existingCustomerWithCard(savedCard)));
        lenient().when(paymentGateway.chargeAndCapture(eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), eq(tier.getTotalPrice()), anyString(), any()))
            .thenReturn("pi_test");
        lenient().when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** L4: a pack bought with a new card moves the payer's subscriptions onto it, like the save-card flow. */
    @Test
    void purchasePack_withANewCard_schedulesTheSubscriptionCardSync() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new");

        verify(subscriptionService).reapplyPaymentMethodAndScheduleSync(PARENT_ID, "pm_old", "pm_new");
    }

    /** fix/pack-expiry-size-tiers: expiry is purchase + the size tier's days (5 sessions → tier2), not 60. */
    @Test
    void purchasePack_stampsExpiryFromThePackSizeTier() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_same");
        when(configService.getBoundedLong(com.softropic.skillars.platform.config.service.ConfigBounds.PACK_EXPIRY_DAYS_TIER2))
            .thenReturn(180L);

        Instant before = Instant.now();
        SessionPackPurchaseResponse response =
            sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_same");
        Instant after = Instant.now();

        assertThat(response.expiresAt())
            .isBetween(before.plus(180, ChronoUnit.DAYS), after.plus(180, ChronoUnit.DAYS));
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void getActiveCoachTier_exposesTheTiersValidityDays() {
        when(sessionPackTierRepository.findByCoachIdAndIsActiveTrue(COACH_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(configService.getBoundedLong(com.softropic.skillars.platform.config.service.ConfigBounds.PACK_EXPIRY_DAYS_TIER2))
            .thenReturn(180L);

        assertThat(sessionPackPaymentService.getActiveCoachTier(COACH_ID).validityDays()).isEqualTo(180L);
    }

    @Test
    void purchasePack_withTheSavedCard_noSync() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_same");

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_same");

        verify(subscriptionService, never()).reapplyPaymentMethodAndScheduleSync(any(), any(), any());
    }

    @Test
    void purchasePack_newCardButTheChargeFails_noSync() {
        SessionPackTier tier = makeActiveTier();
        stubPurchaseWithExistingCustomer(tier, "pm_old");
        when(paymentGateway.chargeAndCapture(eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), eq(tier.getTotalPrice()), anyString(), any()))
            .thenThrow(new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException("payment.declined"));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new"))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class);

        verify(subscriptionService, never()).reapplyPaymentMethodAndScheduleSync(any(), any(), any());
    }

    @Test
    void purchasePack_firstEverCustomer_noSubscriptionToSync() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        // skillars-deferred-174 AC1: a payer with no Stripe customer cannot name a card, so a first purchase names none
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());
        when(paymentGateway.createStripeCustomer(PARENT_ID)).thenReturn("cus_test");
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(stripeCustomerEmailSync.currentEmail(PARENT_ID)).thenReturn("before@example.com");

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null);

        verify(subscriptionService, never()).reapplyPaymentMethodAndScheduleSync(any(), any(), any());
        // skillars-deferred-176 AC2 (RS03e): an email change made while the customer was created is caught after the insert.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(stripeCustomerEmailSync, paymentGateway, stripeCustomerRepository);
        order.verify(stripeCustomerEmailSync).currentEmail(PARENT_ID);
        order.verify(paymentGateway).createStripeCustomer(PARENT_ID);
        order.verify(stripeCustomerRepository).save(any());
        order.verify(stripeCustomerEmailSync).syncIfEmailChangedSince(PARENT_ID, "before@example.com");
    }

    private static final Long PARENT_ID = 8001L;
    private static final Long PLAYER_ID = 8002L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final UUID TIER_ID = UUID.randomUUID();

    @Test
    void purchasePack_playerNotFound_throwsResourceNotFound() {
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Deferred-81 AC4: replaces the old findByIdAndParentId-based
     * purchasePack_playerNotOwnedByParent_throwsResourceNotFound — a parent-owned player wrongly
     * claimed by a different parentId now fails the XOR ownership check with
     * OperationNotAllowedException (mirroring BookingService.createBookingRequest's own wrong-owner
     * behavior) rather than the old 404, which used to mask "exists but not yours" as "not found".
     */
    @Test
    void purchasePack_playerOwnedByDifferentParent_throwsOperationNotAllowed() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setParentId(9999L);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("does not own this player");
    }

    /**
     * Deferred-81 AC4: a self-registered (no-parent) player — parentId null, userId set — must be
     * able to purchase their own session pack, mirroring BookingService.createBookingRequest's own
     * self-booking XOR branch.
     */
    @Test
    void purchasePack_selfRegisteredPlayer_succeeds() {
        Long selfPlayerUserId = 8003L;
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(selfPlayerUserId);

        SessionPackTier tier = makeActiveTier();

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(tier));
        when(stripeCustomerRepository.findById(selfPlayerUserId)).thenReturn(Optional.empty());
        when(paymentGateway.createStripeCustomer(selfPlayerUserId)).thenReturn("cus_test");
        when(stripeCustomerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.chargeAndCapture(eq(TIER_ID), eq(selfPlayerUserId), eq(COACH_ID), eq(tier.getTotalPrice()), anyString(), any()))
            .thenReturn("pi_test");
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SessionPackPurchaseResponse response =
            sessionPackPaymentService.purchasePack(selfPlayerUserId, TIER_ID, PLAYER_ID, null);

        assertThat(response).isNotNull();
        assertThat(response.remainingSessions()).isEqualTo(tier.getSessionCount());
        verify(paymentGateway).chargeAndCapture(eq(TIER_ID), eq(selfPlayerUserId), eq(COACH_ID), eq(tier.getTotalPrice()), anyString(), any());
    }

    /** Deferred-81 AC4: someone else calling with a self-registered player's id must still be refused. */
    @Test
    void purchasePack_selfRegisteredPlayerWrongCaller_throwsNotOwned() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(8003L);

        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null))
            .isInstanceOf(OperationNotAllowedException.class)
            .hasMessageContaining("does not own this profile");
    }

    // ── skillars-deferred-99 AC1: compensating-refund safety ─────────────────────────────────────

    private void stubHappyPathUntilPersist(SessionPackTier tier) {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(tier));
        // skillars-deferred-174 AC1: a named card needs the payer's own Stripe customer, so the happy path has one.
        when(stripeCustomerRepository.findById(PARENT_ID)).thenAnswer(inv -> Optional.of(existingCustomerWithCard(null)));
        lenient().when(stripeCustomerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.chargeAndCapture(eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), eq(tier.getTotalPrice()), anyString(), any()))
            .thenReturn("pi_test");
    }

    @Test
    void purchasePack_persistFails_thenRefundThrowsUnchecked_stillWrapsAndRecordsReconciliation() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        when(sessionPackPurchaseRepository.save(any())).thenThrow(new RuntimeException("db write failed"));
        // An unchecked throw out of refund used to propagate and skip the wrap + reconciliation.
        org.mockito.Mockito.doThrow(new IllegalStateException("stripe SDK blew up"))
            .when(paymentGateway).refund("pi_test", tier.getTotalPrice(), "refund-pi_test");

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test"))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.lifecycleFailure");

        verify(refundReconciliationService).recordRefundFailure(
            org.mockito.ArgumentMatchers.eq("pi_test"),
            org.mockito.ArgumentMatchers.eq(tier.getTotalPrice()),
            org.mockito.ArgumentMatchers.eq(PARENT_ID),
            org.mockito.ArgumentMatchers.eq(TIER_ID),
            any());
    }

    @Test
    void purchasePack_persistFails_thenRefundThrowsPaymentGatewayException_stillWrapsAndRecords() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        when(sessionPackPurchaseRepository.save(any())).thenThrow(new RuntimeException("db write failed"));
        org.mockito.Mockito.doThrow(new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException("payment.refundFailed"))
            .when(paymentGateway).refund("pi_test", tier.getTotalPrice(), "refund-pi_test");

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test"))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.lifecycleFailure");

        verify(refundReconciliationService).recordRefundFailure(
            org.mockito.ArgumentMatchers.eq("pi_test"), any(), any(), any(), any());
    }

    @Test
    void purchasePack_persistFails_butRefundSucceeds_noReconciliationRecord() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        when(sessionPackPurchaseRepository.save(any())).thenThrow(new RuntimeException("db write failed"));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test"))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.lifecycleFailure");

        verify(paymentGateway).refund("pi_test", tier.getTotalPrice(), "refund-pi_test");
        verify(refundReconciliationService, never()).recordRefundFailure(any(), any(), any(), any(), any());
    }

    // ── fix/payment-session-pack-integrity: per-attempt idempotency, one pack per charge ─────────────────

    @Test
    void purchasePack_clientAttemptKey_isTheStripeKey_scopedByPayer() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test", "attempt-0001");

        verify(paymentGateway).chargeAndCapture(TIER_ID, PARENT_ID, COACH_ID, tier.getTotalPrice(),
            "pack-" + PARENT_ID + "-attempt-0001", "pm_test");
    }

    @Test
    void purchasePack_noAttemptKey_everyPurchaseGetsItsOwnStripeKey() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test");
        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test");

        org.mockito.ArgumentCaptor<String> keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(paymentGateway, org.mockito.Mockito.times(2)).chargeAndCapture(
            eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), eq(tier.getTotalPrice()), keys.capture(), any());
        assertThat(keys.getAllValues()).allMatch(k -> k.startsWith("pack-" + PARENT_ID + "-")).doesNotHaveDuplicates();
    }

    @Test
    void purchasePack_replayedCharge_returnsTheExistingPack_createsNoSecond() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        SessionPackPurchase existing = existingPurchase();
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.of(existing));

        SessionPackPurchaseResponse response =
            sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test", "attempt-0001");

        assertThat(response.purchaseId()).isEqualTo(existing.getPurchaseId());
        verify(sessionPackPurchaseRepository, never()).save(any());
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void purchasePack_replayedKeyForADifferentPlayer_conflict_noPack() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        SessionPackPurchase existing = existingPurchase();
        existing.setPlayerId(PLAYER_ID + 1);
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test", "attempt-0001"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.idempotencyKeyReused");
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    /** A concurrent delivery of the same attempt won the insert: its pack holds this charge — never refund it. */
    @Test
    void purchasePack_insertLosesToAConcurrentSameAttempt_returnsTheWinner_noRefund() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        SessionPackPurchase winner = existingPurchase();
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_test"))
            .thenReturn(Optional.empty(), Optional.of(winner));
        when(sessionPackPurchaseRepository.save(any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_spp_stripe_payment_intent_id"));

        SessionPackPurchaseResponse response =
            sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test", "attempt-0001");

        assertThat(response.purchaseId()).isEqualTo(winner.getPurchaseId());
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void purchasePack_insertFailsAndIntentLookupFails_noBlindRefund_reconciliationRecorded() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_test"))
            .thenReturn(Optional.empty())
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        when(sessionPackPurchaseRepository.save(any())).thenThrow(new RuntimeException("db write failed"));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test", "attempt-0001"))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class)
            .hasMessageContaining("payment.lifecycleFailure");
        verify(paymentGateway, never()).refund(any(), any(), any());
        verify(refundReconciliationService).recordRefundFailure(eq("pi_test"), eq(tier.getTotalPrice()),
            eq(PARENT_ID), eq(TIER_ID), startsWith("pack record failed and intent lookup failed"));
    }

    @Test
    void purchasePack_malformedAttemptKey_badRequest_noCharge() {
        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test", "bad key!"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.invalidIdempotencyKey");
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any(), any());
    }

    private SessionPackPurchase existingPurchase() {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(UUID.randomUUID());
        p.setParentId(PARENT_ID);
        p.setPlayerId(PLAYER_ID);
        p.setCoachId(COACH_ID);
        p.setPackTierId(TIER_ID);
        p.setRemainingSessions(5);
        p.setPricePerSession(new BigDecimal("20.00"));
        p.setExpiresAt(Instant.now().plus(60, ChronoUnit.DAYS));
        p.setStripePaymentIntentId("pi_test");
        return p;
    }

    private SessionPackTier makeActiveTier() {
        SessionPackTier tier = new SessionPackTier();
        tier.setPackTierId(TIER_ID);
        tier.setCoachId(COACH_ID);
        tier.setLabel("Starter Pack");
        tier.setSessionCount(5);
        tier.setTotalPrice(new BigDecimal("100.00"));
        tier.setPricePerSession(new BigDecimal("20.00"));
        tier.setActive(true);
        return tier;
    }

    @Test
    void getPacksForParent_computesAllFourStatuses() {
        Instant now = Instant.now();
        SessionPackPurchase active = purchase(3, now.plus(10, ChronoUnit.DAYS), null);
        SessionPackPurchase exhausted = purchase(0, now.plus(10, ChronoUnit.DAYS), null);
        SessionPackPurchase paused = purchase(3, now.plus(10, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS));
        SessionPackPurchase expired = purchase(3, now.minus(1, ChronoUnit.DAYS), null);

        when(sessionPackPurchaseRepository.findByParentIdOrderByCreatedAtDesc(PARENT_ID))
            .thenReturn(List.of(active, exhausted, paused, expired));
        when(sessionPackTierRepository.findAllById(any())).thenReturn(List.of());

        List<SessionPackPurchaseResponse> responses = sessionPackPaymentService.getPacksForParent(PARENT_ID, null);

        assertThat(responses).hasSize(4);
        assertThat(statusOf(responses, active.getPurchaseId())).isEqualTo("ACTIVE");
        assertThat(statusOf(responses, exhausted.getPurchaseId())).isEqualTo("EXHAUSTED");
        assertThat(statusOf(responses, paused.getPurchaseId())).isEqualTo("PAUSED");
        assertThat(statusOf(responses, expired.getPurchaseId())).isEqualTo("EXPIRED");
    }

    /** Pitchside MNY-05: each pack carries the live pause maximum, so the dialog states the real limit. */
    @Test
    void getPacksForParent_reportsTheConfiguredPauseMaximum() {
        SessionPackPurchase active = purchase(3, Instant.now().plus(10, ChronoUnit.DAYS), null);
        when(sessionPackPurchaseRepository.findByParentIdOrderByCreatedAtDesc(PARENT_ID)).thenReturn(List.of(active));
        when(sessionPackTierRepository.findAllById(any())).thenReturn(List.of());
        when(configService.getBoundedLong(com.softropic.skillars.platform.config.service.ConfigBounds.PACK_PAUSE_MAX_DAYS))
            .thenReturn(30L);

        List<SessionPackPurchaseResponse> responses = sessionPackPaymentService.getPacksForParent(PARENT_ID, null);

        assertThat(responses).singleElement().extracting(SessionPackPurchaseResponse::pauseMaxDays).isEqualTo(30);
    }

    /**
     * fix/pack-status-badges: a revoked pack reads REVOKED (not EXHAUSTED, though its sessions were zeroed) and
     * carries its reason; a pack under an open card dispute reads FROZEN (even while paused); a pause scheduled
     * for later leaves the pack ACTIVE with its pausedFrom exposed.
     */
    @Test
    void getPacksForParent_reportsRevokedFrozenAndScheduledPause() {
        Instant now = Instant.now();
        SessionPackPurchase revoked = purchase(0, now.plus(10, ChronoUnit.DAYS), null);
        revoked.setRevokedAt(now.minus(1, ChronoUnit.HOURS));
        revoked.setRevokedReason(SessionPackPurchase.REVOKED_CHARGEBACK_LOST);
        SessionPackPurchase frozen = purchase(3, now.plus(10, ChronoUnit.DAYS), null);
        frozen.setDisputeFrozenAt(now.minus(1, ChronoUnit.HOURS));
        SessionPackPurchase frozenWhilePaused = purchase(3, now.plus(10, ChronoUnit.DAYS),
            now.plus(5, ChronoUnit.DAYS));
        frozenWhilePaused.setDisputeFrozenAt(now.minus(1, ChronoUnit.HOURS));
        SessionPackPurchase scheduledPause = purchase(3, now.plus(10, ChronoUnit.DAYS),
            now.plus(8, ChronoUnit.DAYS));
        scheduledPause.setPausedFrom(now.plus(2, ChronoUnit.DAYS));

        when(sessionPackPurchaseRepository.findByParentIdOrderByCreatedAtDesc(PARENT_ID))
            .thenReturn(List.of(revoked, frozen, frozenWhilePaused, scheduledPause));
        when(sessionPackTierRepository.findAllById(any())).thenReturn(List.of());

        List<SessionPackPurchaseResponse> responses = sessionPackPaymentService.getPacksForParent(PARENT_ID, null);

        assertThat(statusOf(responses, revoked.getPurchaseId())).isEqualTo("REVOKED");
        assertThat(responseOf(responses, revoked.getPurchaseId()).revokedReason()).isEqualTo("CHARGEBACK_LOST");
        assertThat(statusOf(responses, frozen.getPurchaseId())).isEqualTo("FROZEN");
        assertThat(responseOf(responses, frozen.getPurchaseId()).revokedReason()).isNull();
        assertThat(statusOf(responses, frozenWhilePaused.getPurchaseId())).isEqualTo("FROZEN");
        assertThat(statusOf(responses, scheduledPause.getPurchaseId())).isEqualTo("ACTIVE");
        assertThat(responseOf(responses, scheduledPause.getPurchaseId()).pausedFrom())
            .isEqualTo(scheduledPause.getPausedFrom());
    }

    /** fix/pack-status-badges: a revoked pack cannot be extended — refused before any write. */
    @Test
    void extendPack_revokedPack_isRefusedWithPackRevoked() {
        CoachProfile coach = new CoachProfile();
        coach.setId(COACH_ID);
        when(coachProfileRepository.findByUserId(77L)).thenReturn(Optional.of(coach));
        Instant expiresAt = Instant.now().plus(5, ChronoUnit.DAYS);
        SessionPackPurchase revoked = purchase(0, expiresAt, null);
        revoked.setCoachId(COACH_ID);
        revoked.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        revoked.setRevokedReason(SessionPackPurchase.REVOKED_COACH_SUSPENDED);
        when(sessionPackPurchaseRepository.findByIdForUpdate(revoked.getPurchaseId())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> sessionPackPaymentService.extendPack(77L, revoked.getPurchaseId()))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessage("payment.packRevoked");
        assertThat(revoked.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(revoked.getExtendedAt()).isNull();
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    @Test
    void getPacksForParent_filtersByCoachId() {
        Instant now = Instant.now();
        SessionPackPurchase forCoach = purchase(3, now.plus(10, ChronoUnit.DAYS), null);
        forCoach.setCoachId(COACH_ID);

        when(sessionPackPurchaseRepository.findByParentIdAndCoachIdOrderByCreatedAtDesc(PARENT_ID, COACH_ID))
            .thenReturn(List.of(forCoach));
        when(sessionPackTierRepository.findAllById(any())).thenReturn(List.of());

        List<SessionPackPurchaseResponse> responses = sessionPackPaymentService.getPacksForParent(PARENT_ID, COACH_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).purchaseId()).isEqualTo(forCoach.getPurchaseId());
        verify(sessionPackPurchaseRepository, never()).findByParentIdOrderByCreatedAtDesc(any());
    }

    // ─── getSavedPaymentMethod (Deferred-11 AC 4) ─────────────────────────────────

    @Test
    void getSavedPaymentMethod_noStripeCustomerRow_returnsHasCardFalse() {
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        SavedPaymentMethodResponse response = sessionPackPaymentService.getSavedPaymentMethod(PARENT_ID);

        assertThat(response).isEqualTo(new SavedPaymentMethodResponse(false, null, null, null, null));
    }

    @Test
    void getSavedPaymentMethod_rowWithNullPaymentMethodId_returnsHasCardFalse() {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setStripeCustomerId("cus_test");
        customer.setStripePaymentMethodId(null);
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));

        SavedPaymentMethodResponse response = sessionPackPaymentService.getSavedPaymentMethod(PARENT_ID);

        assertThat(response).isEqualTo(new SavedPaymentMethodResponse(false, null, null, null, null));
    }

    @Test
    void getSavedPaymentMethod_rowWithPaymentMethodId_returnsHasCardTrueWithDetails() throws Exception {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setStripeCustomerId("cus_test");
        customer.setStripePaymentMethodId("pm_test_123");
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));

        PaymentMethod.Card card = new PaymentMethod.Card();
        card.setBrand("visa");
        card.setLast4("4242");
        card.setExpMonth(12L);
        card.setExpYear(2030L);
        PaymentMethod paymentMethod = new PaymentMethod();
        paymentMethod.setCard(card);
        when(stripeClient.retrievePaymentMethod("pm_test_123")).thenReturn(paymentMethod);

        SavedPaymentMethodResponse response = sessionPackPaymentService.getSavedPaymentMethod(PARENT_ID);

        assertThat(response).isEqualTo(new SavedPaymentMethodResponse(true, "visa", "4242", 12L, 2030L));
    }

    @Test
    void getSavedPaymentMethod_stripeErrorsOnRetrieve_returnsHasCardTrueWithNullDetails() throws Exception {
        // AC 4: a parent must never be told they have no card when they do — a transient Stripe
        // failure on retrieve must not downgrade hasCard to false.
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(PARENT_ID);
        customer.setStripeCustomerId("cus_test");
        customer.setStripePaymentMethodId("pm_test_123");
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(customer));
        when(stripeClient.retrievePaymentMethod("pm_test_123"))
            .thenThrow(new com.stripe.exception.ApiException("Stripe unavailable", "req_1", null, 500, null));

        SavedPaymentMethodResponse response = sessionPackPaymentService.getSavedPaymentMethod(PARENT_ID);

        assertThat(response).isEqualTo(new SavedPaymentMethodResponse(true, null, null, null, null));
    }

    private String statusOf(List<SessionPackPurchaseResponse> responses, UUID purchaseId) {
        return responses.stream()
            .filter(r -> r.purchaseId().equals(purchaseId))
            .findFirst()
            .orElseThrow()
            .status();
    }

    private SessionPackPurchaseResponse responseOf(List<SessionPackPurchaseResponse> responses, UUID purchaseId) {
        return responses.stream().filter(r -> r.purchaseId().equals(purchaseId)).findFirst().orElseThrow();
    }

    private SessionPackPurchase purchase(int remainingSessions, Instant expiresAt, Instant pausedUntil) {
        SessionPackPurchase p = new SessionPackPurchase();
        p.setPurchaseId(UUID.randomUUID());
        p.setParentId(PARENT_ID);
        p.setPlayerId(PLAYER_ID);
        p.setCoachId(COACH_ID);
        p.setPackTierId(TIER_ID);
        p.setPricePerSession(new BigDecimal("25.00"));
        p.setRemainingSessions(remainingSessions);
        p.setExpiresAt(expiresAt);
        p.setPausedUntil(pausedUntil);
        return p;
    }

    // ── skillars-deferred-169: the attempt record (RS06), the card revert (RS09) and the resolver (AC2) ──────────

    private static final String KEY = "attempt-key-0001";

    private com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt attempt(
        com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status status, long ageMinutes) {
        com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt a =
            new com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt(
                PARENT_ID, KEY, TIER_ID, PLAYER_ID, "pm_old", "pm_new", true);
        a.setId(77L);
        a.setStatus(status);
        a.setCreatedAt(Instant.now().minus(ageMinutes, ChronoUnit.MINUTES));
        return a;
    }

    private void stubGatesOnly() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
    }

    private void minAgeAndAbsenceAge(long minAge, long absenceAge) {
        lenient().when(configService.getBoundedLong(com.softropic.skillars.platform.config.service.ConfigBounds.PACK_ATTEMPT_MIN_AGE_MINUTES))
            .thenReturn(minAge);
        lenient().when(configService.getBoundedLong(com.softropic.skillars.platform.config.service.ConfigBounds.PACK_ATTEMPT_ABSENCE_AGE_MINUTES))
            .thenReturn(absenceAge);
    }

    @Test
    void purchasePack_cardDecline_marksNoCharge_revertsTheCardFirst_andSignalsNoChargeMade() {
        SessionPackTier tier = makeActiveTier();
        stubPurchaseWithExistingCustomer(tier, "pm_old");
        when(paymentGateway.chargeAndCapture(any(), any(), any(), any(), anyString(), any()))
            .thenThrow(new PaymentGatewayException("payment.lifecycleFailure"));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PackNoChargeException.class)
            .hasMessage("payment.noChargeMade");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(stripeCustomerRepository, attemptRepository);
        order.verify(stripeCustomerRepository).revertPaymentMethod(PARENT_ID, "pm_old", "pm_new");
        order.verify(attemptRepository).transition(any(), eq(
            com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any());
        verify(subscriptionService, never()).reapplyPaymentMethodAndScheduleSync(any(), any(), any());
    }

    @Test
    void purchasePack_aRefusedCard_keepsItsOwnCode_soTheUiCanOfferToUpdateTheCard_andIsStillNoCharge() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        when(paymentGateway.chargeAndCapture(any(), any(), any(), any(), anyString(), any()))
            .thenThrow(new PaymentGatewayException(PaymentGatewayException.CARD_DECLINED));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PackNoChargeException.class)
            .hasMessage("payment.cardDeclined");

        verify(stripeCustomerRepository).revertPaymentMethod(PARENT_ID, "pm_old", "pm_new");
    }

    @Test
    void purchasePack_successAfterTheResolverWroteNoCharge_isAllowedToMoveNoChargeToSucceeded() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.empty());
        // the resolver already wrote NO_CHARGE: the write from the open statuses loses, the one from NO_CHARGE wins
        when(attemptRepository.transition(any(), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.SUCCEEDED),
            eq("pi_test"), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.OPEN))).thenReturn(false);

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY);

        // proof of capture beats a resolver's inference from absence (fourth review, H1); skillars-deferred-174 AC4 tags it
        verify(attemptRepository).transition(any(), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.SUCCEEDED),
            eq("pi_test"), eq(java.util.Set.of(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE)));
    }

    @Test
    void purchasePack_aNamedCardNotOnThePayersCustomer_isRefusedBeforeAnyWrite() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(existingCustomerWithCard("pm_old")));
        when(paymentGateway.paymentMethodBelongsTo("cus_test", "pm_foreign")).thenReturn(false);

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_foreign", KEY))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.paymentMethodNotOwned");

        verify(attemptRepository, never()).saveAndFlush(any());
        verify(stripeCustomerRepository, never()).save(any());
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any(), any());
    }

    @Test
    void purchasePack_aNamedCardWithoutAStripeCustomer_isRefused_noCustomerIsCreated() {
        PlayerProfile player = new PlayerProfile();
        player.setId(PLAYER_ID);
        player.setUserId(PARENT_ID);
        when(playerProfileRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.paymentMethodNotOwned");

        verify(paymentGateway, never()).createStripeCustomer(any());
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    /** skillars-deferred-174 AC3 (RS09d): the card charged is the one the request named, whatever the column holds by then. */
    @Test
    void purchasePack_chargesTheNamedCard_evenWhenTheResolverRevertedTheColumnBeforeTheCharge() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.empty());
        // the RS09d interleaving: after the card write, before the charge, the resolver put the previous card back
        when(stripeCustomerCardWrites.writeCardIfPayerLive(PARENT_ID, "pm_new")).thenAnswer(inv -> {
            stripeCustomerRepository.revertPaymentMethod(PARENT_ID, "pm_old", "pm_new");
            return true;
        });

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY);

        verify(paymentGateway).chargeAndCapture(eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), any(), anyString(), eq("pm_new"));
    }

    @Test
    void purchasePack_withoutANamedCard_chargesTheSavedCard() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.empty());

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null, KEY);

        verify(paymentGateway).chargeAndCapture(eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), any(), anyString(),
            org.mockito.ArgumentMatchers.isNull());
        verify(paymentGateway, never()).paymentMethodBelongsTo(any(), any());
    }

    /** skillars-deferred-174 AC4: a card saved by someone else during the purchase is kept (the CAS reports it lost). */
    @Test
    void purchasePack_aCardSavedDuringThePurchase_isKept() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_test")).thenReturn(Optional.empty());
        when(subscriptionService.reapplyPaymentMethodAndScheduleSync(PARENT_ID, "pm_old", "pm_new")).thenReturn(false);

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY);

        verify(subscriptionService, never()).savePaymentMethodAndScheduleSync(any(), any());
    }

    @Test
    void purchasePack_declineWithNoCardAttempted_doesNotTouchTheSavedCard() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        when(paymentGateway.chargeAndCapture(any(), any(), any(), any(), anyString(), any()))
            .thenThrow(new PaymentGatewayException("payment.lifecycleFailure"));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, null, KEY))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PackNoChargeException.class);

        verify(stripeCustomerRepository, never()).revertPaymentMethod(any(), any(), any());
    }

    @Test
    void purchasePack_outcomeUnknown_marksUnknown_neverRevertsTheCard_andRethrows() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        when(paymentGateway.chargeAndCapture(any(), any(), any(), any(), anyString(), any()))
            .thenThrow(new com.softropic.skillars.platform.payment.contract.exception.ChargeOutcomeUnknownException("pack-key", null));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.ChargeOutcomeUnknownException.class);

        verify(attemptRepository).transition(any(), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN), any(), any());
        verify(stripeCustomerRepository, never()).revertPaymentMethod(any(), any(), any());
    }

    @Test
    void purchasePack_unclassifiedRuntimeException_isUnknown_notLeftInProgress() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        when(paymentGateway.chargeAndCapture(any(), any(), any(), any(), anyString(), any())).thenThrow(new IllegalStateException("pool timeout"));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .isInstanceOf(IllegalStateException.class);

        verify(attemptRepository).transition(any(), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN), any(), any());
        verify(stripeCustomerRepository, never()).revertPaymentMethod(any(), any(), any());
    }

    @Test
    void purchasePack_aSecondOpenAttemptOfTheParent_isRefusedBeforeAnyCardWriteOrCharge() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("uq_sppa_one_open_per_parent")).when(attemptRepository).saveAndFlush(any());
        when(attemptRepository.findByParentIdAndAttemptKey(PARENT_ID, KEY)).thenReturn(Optional.empty());
        when(attemptRepository.findOpenByParentId(eq(PARENT_ID), any()))
            .thenReturn(Optional.of(attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN, 3)));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.attemptUnresolved");

        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any(), any());
        verify(stripeCustomerRepository, never()).save(any());
    }

    @Test
    void purchasePack_concurrentSameKey_answersFromTheWinnerRow() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("uq_sppa_parent_key")).when(attemptRepository).saveAndFlush(any());
        when(attemptRepository.findByParentIdAndAttemptKey(PARENT_ID, KEY))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS, 0)));
        minAgeAndAbsenceAge(10, 30);

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.attemptInProgress");
    }

    @Test
    void replay_succeededAttempt_returnsTheRecordedPack_withoutCallingStripe() {
        stubGatesOnly();
        var a = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.SUCCEEDED, 60);
        a.setPaymentIntentId("pi_done");
        when(attemptRepository.findByParentIdAndAttemptKey(PARENT_ID, KEY)).thenReturn(Optional.of(a));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        SessionPackPurchase pack = new SessionPackPurchase();
        pack.setPlayerId(PLAYER_ID);
        pack.setPackTierId(TIER_ID);
        pack.setStripePaymentIntentId("pi_done");
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_done")).thenReturn(Optional.of(pack));

        SessionPackPurchaseResponse response = sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY);

        assertThat(response).isNotNull();
        verifyNoStripeCalls();
    }

    @Test
    void replay_afterTheTierWasDeactivated_stillAnswers_theGatesRunOnlyForNewAttempts() {
        stubGatesOnly();
        var a = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE, 60);
        when(attemptRepository.findByParentIdAndAttemptKey(PARENT_ID, KEY)).thenReturn(Optional.of(a));
        SessionPackTier inactive = makeActiveTier();
        inactive.setActive(false);
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PackNoChargeException.class);
    }

    @Test
    void replay_openAttemptYoungerThanTheMinimumAge_isRefusedAsInProgress() {
        stubGatesOnly();
        minAgeAndAbsenceAge(10, 30);
        when(attemptRepository.findByParentIdAndAttemptKey(PARENT_ID, KEY))
            .thenReturn(Optional.of(attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS, 2)));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new", KEY))
            .hasMessageContaining("payment.attemptInProgress");
        verifyNoStripeCalls();
    }

    @Test
    void replay_sameKeyDifferentPack_openRow_isAttemptUnresolved_terminalRow_isKeyReused() {
        stubGatesOnly();
        UUID otherTier = UUID.randomUUID();
        when(attemptRepository.findByParentIdAndAttemptKey(PARENT_ID, KEY))
            .thenReturn(Optional.of(attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN, 3)))
            .thenReturn(Optional.of(attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.SUCCEEDED, 3)));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, otherTier, PLAYER_ID, "pm_new", KEY))
            .hasMessageContaining("payment.attemptUnresolved");
        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, otherTier, PLAYER_ID, "pm_new", KEY))
            .hasMessageContaining("payment.idempotencyKeyReused");
    }

    private void verifyNoStripeCalls() {
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any(), any());
        verify(paymentGateway, never()).findChargeByIdempotencyKey(any(), any(), any());
        verify(paymentGateway, never()).getCaptureState(any());
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    // -- resolveAttempt (AC2) --

    private void openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status status, long ageMinutes) {
        var a = attempt(status, ageMinutes);
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(a));
        lenient().when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(existingCustomerWithCard("pm_new")));
    }

    private com.softropic.skillars.platform.payment.contract.ChargeLookup lookup(String status) {
        return new com.softropic.skillars.platform.payment.contract.ChargeLookup("pi_found", status, new BigDecimal("100.00"));
    }

    @Test
    void resolve_nothingFound_insideTheAbsenceAge_staysOpen_pastIt_isNoCharge_andRevertsTheCard() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS, 15);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());

        var young = sessionPackPaymentService.resolveAttempt(77L);
        assertThat(young.status()).isEqualTo(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS);
        verify(attemptRepository, never()).transition(any(), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any());
        verify(stripeCustomerRepository, never()).revertPaymentMethod(any(), any(), any());
    }

    @Test
    void resolve_nothingFound_pastTheAbsenceAge_isNoCharge() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN, 45);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());

        sessionPackPaymentService.resolveAttempt(77L);

        verify(stripeCustomerRepository).revertPaymentMethod(PARENT_ID, "pm_old", "pm_new");
        verify(attemptRepository).transition(eq(77L), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any());
    }

    @Test
    void resolve_noCustomerRow_isAnAbsenceVerdict_soItNeedsTheAbsenceAgeToo() {
        minAgeAndAbsenceAge(10, 30);
        var a = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS, 15);
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(a));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());

        sessionPackPaymentService.resolveAttempt(77L);

        verify(attemptRepository, never()).transition(any(), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any());
        verify(paymentGateway, never()).findChargeByIdempotencyKey(any(), any(), any());
    }

    @Test
    void resolve_failedLookup_staysOpen() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN, 120);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenThrow(new PaymentGatewayException("payment.chargeLookupFailed"));

        var result = sessionPackPaymentService.resolveAttempt(77L);

        assertThat(result.status().isOpen()).isTrue();
        verify(attemptRepository, never()).transition(any(), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any());
    }

    @Test
    void resolve_foundButNotSucceeded_isNoCharge_andNeverReachesTheCaptureState() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN, 12);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("requires_payment_method")));

        sessionPackPaymentService.resolveAttempt(77L);

        verify(paymentGateway, never()).getCaptureState(any());
        verify(attemptRepository).transition(eq(77L), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any());
    }

    @Test
    void resolve_pendingIntent_staysUnknown() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS, 12);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("processing")));

        var result = sessionPackPaymentService.resolveAttempt(77L);

        assertThat(result.status()).isEqualTo(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN);
        verify(paymentGateway, never()).getCaptureState(any());
    }

    @Test
    void resolve_succeededAndFullyRefunded_isRefunded_createsNoPack() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNRECONCILED, 12);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("succeeded")));
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_found")).thenReturn(Optional.empty());
        when(paymentGateway.getCaptureState("pi_found")).thenReturn(
            new com.softropic.skillars.platform.payment.contract.CaptureState(new BigDecimal("100.00"), new BigDecimal("100.00")));

        sessionPackPaymentService.resolveAttempt(77L);

        verify(sessionPackPurchaseRepository, never()).save(any());
        verify(attemptRepository).transition(eq(77L), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.REFUNDED), eq("pi_found"), any());
    }

    @Test
    void resolve_succeededWithAnUnreadableCharge_isNotTreatedAsRefunded_staysOpen() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN, 12);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("succeeded")));
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_found")).thenReturn(Optional.empty());
        when(paymentGateway.getCaptureState("pi_found")).thenReturn(
            new com.softropic.skillars.platform.payment.contract.CaptureState(null, BigDecimal.ZERO));

        var result = sessionPackPaymentService.resolveAttempt(77L);

        assertThat(result.status().isOpen()).isTrue();
        verify(sessionPackPurchaseRepository, never()).save(any());
        verify(attemptRepository, never()).transition(any(), eq(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.REFUNDED), any(), any());
    }

    @Test
    void resolve_capturedAndNotRefunded_createsThePackOnce_closesTheRefundFailure_andRunsTheCardSync() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNRECONCILED, 12);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("succeeded")));
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_found")).thenReturn(Optional.empty());
        when(paymentGateway.getCaptureState("pi_found")).thenReturn(
            new com.softropic.skillars.platform.payment.contract.CaptureState(new BigDecimal("100.00"), BigDecimal.ZERO));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var failure = new com.softropic.skillars.platform.payment.repo.StripeRefundFailure("pi_found", new BigDecimal("100.00"), PARENT_ID, TIER_ID, "x");
        when(refundFailureRepository.findByPaymentIntentId("pi_found")).thenReturn(Optional.of(failure));

        var result = sessionPackPaymentService.resolveAttempt(77L);

        assertThat(result.status()).isEqualTo(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.SUCCEEDED);
        verify(sessionPackPurchaseRepository).save(any());
        assertThat(failure.getResolvedAt()).isNotNull();
        verify(subscriptionService).reapplyPaymentMethodAndScheduleSync(PARENT_ID, "pm_old", "pm_new");
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void resolve_createPurchaseFails_neverRefunds_staysOpenForTheNextCycle() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNRECONCILED, 12);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("succeeded")));
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_found")).thenReturn(Optional.empty());
        when(paymentGateway.getCaptureState("pi_found")).thenReturn(
            new com.softropic.skillars.platform.payment.contract.CaptureState(new BigDecimal("100.00"), BigDecimal.ZERO));
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(sessionPackPurchaseRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        var result = sessionPackPaymentService.resolveAttempt(77L);

        assertThat(result.status().isOpen()).isTrue();
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void resolve_existingPackForTheIntent_isAdopted_noSecondPack() {
        minAgeAndAbsenceAge(10, 30);
        var a = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNKNOWN, 12);
        a.setPaymentIntentId("pi_known");
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(a));
        SessionPackPurchase pack = new SessionPackPurchase();
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_known")).thenReturn(Optional.of(pack));

        var result = sessionPackPaymentService.resolveAttempt(77L);

        assertThat(result.status()).isEqualTo(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.SUCCEEDED);
        verify(sessionPackPurchaseRepository, never()).save(any());
        verifyNoStripeCalls();
    }

    // ── skillars-deferred-174 AC6 (RS06e): the job marks the rows only an operator can close ─────────────────────────

    private static final com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status UNRECONCILED =
        com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.UNRECONCILED;

    private com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt queuedForTheJob(long ageMinutes) {
        minAgeAndAbsenceAge(10, 30);
        lenient().when(configService.getBoundedLong(
            com.softropic.skillars.platform.config.service.ConfigBounds.PACK_ATTEMPT_OPERATOR_AFTER_MINUTES)).thenReturn(60L);
        var a = attempt(UNRECONCILED, ageMinutes);
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(a));
        lenient().when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(existingCustomerWithCard("pm_new")));
        when(attemptRepository.findOpenOlderThan(any(), any())).thenReturn(List.of(a));
        lenient().when(attemptRepository.markNeedsOperator(eq(77L), any())).thenReturn(1);
        return a;
    }

    private void capturedWithoutAPack() {
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("succeeded")));
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_found")).thenReturn(Optional.empty());
        when(paymentGateway.getCaptureState("pi_found")).thenReturn(
            new com.softropic.skillars.platform.payment.contract.CaptureState(new BigDecimal("100.00"), BigDecimal.ZERO));
    }

    @Test
    void job_tierGone_marksTheRowForAnOperatorAtOnce() {
        queuedForTheJob(12);
        capturedWithoutAPack();
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.empty());

        assertThat(sessionPackPaymentService.resolveStaleAttempts()).isZero();

        verify(attemptRepository).markNeedsOperator(eq(77L), any());
        verify(sessionPackPurchaseRepository, never()).save(any());
    }

    @Test
    void job_recordFailed_isRetried_untilTheAttemptIsOlderThanTheThreshold() {
        queuedForTheJob(59);
        capturedWithoutAPack();
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(sessionPackPurchaseRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        sessionPackPaymentService.resolveStaleAttempts();

        verify(attemptRepository, never()).markNeedsOperator(any(), any());
    }

    @Test
    void job_recordFailed_pastTheThreshold_isMarked() {
        queuedForTheJob(61);
        capturedWithoutAPack();
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(sessionPackPurchaseRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        sessionPackPaymentService.resolveStaleAttempts();

        verify(attemptRepository).markNeedsOperator(eq(77L), any());
    }

    @Test
    void job_unreadableCharge_pastTheThreshold_isMarked() {
        queuedForTheJob(61);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("succeeded")));
        when(sessionPackPurchaseRepository.findByStripePaymentIntentId("pi_found")).thenReturn(Optional.empty());
        when(paymentGateway.getCaptureState("pi_found")).thenReturn(new com.softropic.skillars.platform.payment.contract.CaptureState(null, BigDecimal.ZERO));

        sessionPackPaymentService.resolveStaleAttempts();

        verify(attemptRepository).markNeedsOperator(eq(77L), any());
    }

    @Test
    void job_aNoChargeWriteThatDoesNotLand_pastTheThreshold_isMarked() {
        // An UNRECONCILED row Stripe shows no charge for: the job never closes it as NO_CHARGE, and (skillars-deferred-176
        // AC4, RS06i) never reverts the card a capture was seen with
        queuedForTheJob(61);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());

        assertThat(sessionPackPaymentService.resolveStaleAttempts()).isZero();

        verify(attemptRepository).markNeedsOperator(eq(77L), any());
        verify(attemptRepository, never()).transition(any(), any(), any(), any());
        verify(stripeCustomerRepository, never()).revertPaymentMethod(any(), any(), any());
    }

    @Test
    void job_aStripeOutage_isNeverMarked_howeverOldTheAttempt() {
        queuedForTheJob(24 * 60);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any()))
            .thenThrow(new PaymentGatewayException("payment.chargeLookupFailed"));

        sessionPackPaymentService.resolveStaleAttempts();

        verify(attemptRepository, never()).markNeedsOperator(any(), any());
    }

    // ── skillars-deferred-174 AC5 (RS06b): the admin close action ────────────────────────────────────────────────────

    private static final Long ADMIN_ID = 5L;

    @Test
    void close_unknownAttempt_is404() {
        when(attemptRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(99L, ADMIN_ID, "stuck"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void close_aTerminalAttempt_is409NotOpen() {
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(
            attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.SUCCEEDED, 120)));

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.packAttempt.notOpen");
        verify(attemptRepository, never()).recordAdminClose(any(), any(), any());
    }

    @Test
    void close_nothingChargedPastTheAbsenceAge_closesAsNoCharge_throughTheCardRevert_andRecordsWhoAndWhy() {
        minAgeAndAbsenceAge(10, 30);
        var open = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS, 120);
        var closed = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE, 120);
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(open), Optional.of(open), Optional.of(closed));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(existingCustomerWithCard("pm_new")));
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());

        var response = sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "parent blocked since Monday");

        assertThat(response.status()).isEqualTo("NO_CHARGE");
        verify(stripeCustomerRepository).revertPaymentMethod(PARENT_ID, "pm_old", "pm_new");
        verify(attemptRepository).recordAdminClose(77L, ADMIN_ID, "parent blocked since Monday");
    }

    @Test
    void close_whenRecordingWhoClosedItFails_theCloseStillStands() {
        minAgeAndAbsenceAge(10, 30);
        var open = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS, 120);
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(open));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(existingCustomerWithCard("pm_new")));
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());
        when(attemptRepository.recordAdminClose(any(), any(), any())).thenThrow(new IllegalStateException("db blip"));

        var response = sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck");

        assertThat(response.status()).isEqualTo("NO_CHARGE");
    }

    @Test
    void close_aRowClosedElsewhereMeanwhile_is409NotOpen_andIsNotRecordedAsThisAdminsClose() {
        minAgeAndAbsenceAge(10, 30);
        var open = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.IN_PROGRESS, 120);
        var closedByTheJob = attempt(com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE, 120);
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(open), Optional.of(open), Optional.of(closedByTheJob));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(existingCustomerWithCard("pm_new")));
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());
        when(attemptRepository.transition(eq(77L), eq(
            com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.packAttempt.notOpen");
        verify(attemptRepository, never()).recordAdminClose(any(), any(), any());
    }

    // skillars-deferred-176 AC4 (RS06i): only the operator closes an UNRECONCILED row Stripe shows no succeeded charge for
    @Test
    void close_anUnreconciledAttemptWithNoCharge_closesAsNoCharge_withoutTheRevert_andResolvesItsRefundFailure() {
        minAgeAndAbsenceAge(10, 30);
        var open = attempt(UNRECONCILED, 120);
        open.setPaymentIntentId("pi_seen");
        when(attemptRepository.findById(77L)).thenReturn(Optional.of(open));
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.of(existingCustomerWithCard("pm_new")));
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());
        when(attemptRepository.transition(77L, com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE,
            null, java.util.EnumSet.of(UNRECONCILED))).thenReturn(true);
        var failure = new com.softropic.skillars.platform.payment.repo.StripeRefundFailure("pi_seen", new BigDecimal("100.00"), PARENT_ID, TIER_ID, "x");
        when(refundFailureRepository.findByPaymentIntentId("pi_seen")).thenReturn(Optional.of(failure));

        var response = sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "Stripe lost the charge");

        assertThat(response.status()).isEqualTo("NO_CHARGE");
        verify(stripeCustomerRepository, never()).revertPaymentMethod(any(), any(), any());
        assertThat(failure.getResolvedAt()).isNotNull();
        verify(refundFailureRepository).save(failure);
        verify(attemptRepository).recordAdminClose(77L, ADMIN_ID, "Stripe lost the charge");
    }

    @Test
    void close_aNoChargeWriteThatDoesNotLand_is409WriteLost_notAStripeFailure() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(UNRECONCILED, 120);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());
        when(attemptRepository.transition(eq(77L), eq(
            com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.packAttempt.writeLost");
        verify(attemptRepository, never()).recordAdminClose(any(), any(), any());
    }

    @Test
    void close_aProcessingCharge_isRefused409_andStaysOpen() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(UNRECONCILED, 120);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.of(lookup("processing")));

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.packAttempt.chargePending");
        verify(attemptRepository, never()).recordAdminClose(any(), any(), any());
        verify(attemptRepository, never()).transition(any(), eq(
            com.softropic.skillars.platform.payment.repo.SessionPackPurchaseAttempt.Status.NO_CHARGE), any(), any());
    }

    @Test
    void close_noChargeYetInsideTheAbsenceAge_isRefused409TooRecent() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(UNRECONCILED, 12);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.packAttempt.tooRecent");
        verify(attemptRepository, never()).transition(any(), any(), any(), any());
    }

    @Test
    void close_capturedWithNoPackAfterTheRetry_isRefused409CapturedNoPack() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(UNRECONCILED, 120);
        capturedWithoutAPack();
        when(sessionPackTierRepository.findById(TIER_ID)).thenReturn(Optional.of(makeActiveTier()));
        when(sessionPackPurchaseRepository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("payment.packAttempt.capturedNoPack");
        // the resolver retried recording the pack first
        verify(sessionPackPurchaseRepository).save(any());
        verify(paymentGateway, never()).refund(any(), any(), any());
    }

    @Test
    void close_whenStripeCannotBeAsked_isA422GatewayError() {
        minAgeAndAbsenceAge(10, 30);
        openAttemptInDb(UNRECONCILED, 120);
        when(paymentGateway.findChargeByIdempotencyKey(any(), anyString(), any()))
            .thenThrow(new PaymentGatewayException("payment.chargeLookupFailed"));

        assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck"))
            .isInstanceOf(PaymentGatewayException.class);
        verify(attemptRepository, never()).recordAdminClose(any(), any(), any());
    }

    @Test
    void close_byALimitedAdmin_isDenied() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("ltd", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    com.softropic.skillars.infrastructure.security.SecurityConstants.ROLE_LTD_ADMIN))));
        try {
            assertThatThrownBy(() -> sessionPackPaymentService.closeAttemptByAdmin(77L, ADMIN_ID, "stuck"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            verify(attemptRepository, never()).findById(any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
