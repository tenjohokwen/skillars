package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.contract.PaymentGateway;
import com.softropic.skillars.platform.payment.contract.SavedPaymentMethodResponse;
import com.softropic.skillars.platform.payment.contract.SessionPackPurchaseResponse;
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

    @InjectMocks SessionPackPaymentService sessionPackPaymentService;

    @BeforeEach
    void setUpLockRetryer() {
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        // fix/payment-pack-integrity: purchasePack checks the tier's coach is bookable.
        com.softropic.skillars.platform.marketplace.repo.CoachProfile coach =
            new com.softropic.skillars.platform.marketplace.repo.CoachProfile();
        coach.setStatus(com.softropic.skillars.platform.marketplace.contract.CoachProfileStatus.ACTIVE);
        lenient().when(coachProfileRepository.findById(COACH_ID)).thenReturn(Optional.of(coach));
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
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any());
        verify(stripeCustomerRepository, never()).save(any());
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
        lenient().when(paymentGateway.chargeAndCapture(eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), eq(tier.getTotalPrice()), anyString()))
            .thenReturn("pi_test");
        lenient().when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** L4: a pack bought with a new card moves the payer's subscriptions onto it, like the save-card flow. */
    @Test
    void purchasePack_withANewCard_schedulesTheSubscriptionCardSync() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_old");

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new");

        verify(subscriptionService).savePaymentMethodAndScheduleSync(PARENT_ID, "pm_new");
    }

    @Test
    void purchasePack_withTheSavedCard_noSync() {
        stubPurchaseWithExistingCustomer(makeActiveTier(), "pm_same");

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_same");

        verify(subscriptionService, never()).savePaymentMethodAndScheduleSync(any(), any());
    }

    @Test
    void purchasePack_newCardButTheChargeFails_noSync() {
        SessionPackTier tier = makeActiveTier();
        stubPurchaseWithExistingCustomer(tier, "pm_old");
        when(paymentGateway.chargeAndCapture(eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), eq(tier.getTotalPrice()), anyString()))
            .thenThrow(new com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException("payment.declined"));

        assertThatThrownBy(() -> sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_new"))
            .isInstanceOf(com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException.class);

        verify(subscriptionService, never()).savePaymentMethodAndScheduleSync(any(), any());
    }

    @Test
    void purchasePack_firstEverCustomer_noSubscriptionToSync() {
        SessionPackTier tier = makeActiveTier();
        stubHappyPathUntilPersist(tier);
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sessionPackPaymentService.purchasePack(PARENT_ID, TIER_ID, PLAYER_ID, "pm_test");

        verify(subscriptionService, never()).savePaymentMethodAndScheduleSync(any(), any());
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
        when(paymentGateway.chargeAndCapture(eq(TIER_ID), eq(selfPlayerUserId), eq(COACH_ID), eq(tier.getTotalPrice()), anyString()))
            .thenReturn("pi_test");
        when(sessionPackPurchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SessionPackPurchaseResponse response =
            sessionPackPaymentService.purchasePack(selfPlayerUserId, TIER_ID, PLAYER_ID, "pm_test");

        assertThat(response).isNotNull();
        assertThat(response.remainingSessions()).isEqualTo(tier.getSessionCount());
        verify(paymentGateway).chargeAndCapture(eq(TIER_ID), eq(selfPlayerUserId), eq(COACH_ID), eq(tier.getTotalPrice()), anyString());
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
        when(stripeCustomerRepository.findById(PARENT_ID)).thenReturn(Optional.empty());
        when(paymentGateway.createStripeCustomer(PARENT_ID)).thenReturn("cus_test");
        when(stripeCustomerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentGateway.chargeAndCapture(eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), eq(tier.getTotalPrice()), anyString()))
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
            "pack-" + PARENT_ID + "-attempt-0001");
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
            eq(TIER_ID), eq(PARENT_ID), eq(COACH_ID), eq(tier.getTotalPrice()), keys.capture());
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
        verify(paymentGateway, never()).chargeAndCapture(any(), any(), any(), any(), any());
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
}
