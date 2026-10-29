package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.marketplace.contract.CoachSubscriptionTier;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscription;
import com.softropic.skillars.platform.marketplace.repo.CoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.contract.SubscriptionEntitlement;
import com.softropic.skillars.platform.payment.contract.event.SubscriptionExpiredEvent;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChange;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PlayerSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.repo.ParentPlayerLinkRepository;
import com.softropic.skillars.platform.security.repo.PlayerProfileRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import jakarta.persistence.Version;
import org.instancio.Instancio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.instancio.Select.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix/payment-subscription-entitlements: one focused case per audited problem (items 1–11). Uses the
 * real SubscriptionService with mocked repositories/Stripe; {@code self} is the service itself (no
 * proxy needed — the transactional boundaries are covered by SubscriptionLifecycleIT).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionEntitlementIntegrityTest {

    @Mock PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock CoachSubscriptionChangeRepository coachSubscriptionChangeRepository;
    @Mock PlayerSubscriptionChangeRepository playerSubscriptionChangeRepository;
    @Mock CoachSubscriptionRepository coachSubscriptionRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock ConfigService configService;
    @Mock StripeClient stripeClient;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ParentPlayerLinkRepository parentPlayerLinkRepository;
    @Mock TransactionTemplate transactionTemplate;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock PessimisticLockRetryer lockRetryer;
    @Mock StripeWebhookService stripeWebhookService;
    @Mock PlayerProfileRepository playerProfileRepository;

    @InjectMocks SubscriptionService service;

    private static final UUID COACH_ID = UUID.randomUUID();
    private static final long COACH_USER_ID = 5001L;
    private static final long PLAYER_ID = 6001L;
    private static final long PARENT_ID = 6002L;
    private static final String SUB_ID = "sub_live_1";

    private PaymentCoachSubscription coachSub;
    private CoachSubscription marketplace;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "self", service);
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(coachProfileRepository.findByIdForUpdate(any()))
            .thenReturn(Optional.of(Instancio.create(CoachProfile.class)));

        coachSub = new PaymentCoachSubscription();
        coachSub.setCoachId(COACH_ID);
        coachSub.setTier("ACADEMY");
        coachSub.setStatus("ACTIVE");
        coachSub.setStripeSubscriptionId(SUB_ID);
        coachSub.setCurrentPeriodEnd(Instant.now().plus(10, ChronoUnit.DAYS));
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(coachSub));
        when(paymentCoachSubscriptionRepository.findByCoachIdForUpdate(COACH_ID)).thenReturn(Optional.of(coachSub));
        when(paymentCoachSubscriptionRepository.findByStripeSubscriptionIdForUpdate(SUB_ID))
            .thenAnswer(inv -> SUB_ID.equals(coachSub.getStripeSubscriptionId())
                ? Optional.of(coachSub) : Optional.empty());

        marketplace = new CoachSubscription();
        marketplace.setCoachId(COACH_ID);
        marketplace.setTier(CoachSubscriptionTier.ACADEMY);
        when(coachSubscriptionRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(marketplace));

        when(configService.getString("subscription.coach.instructor.monthly.priceId")).thenReturn("price_instr");
        when(configService.getString("subscription.coach.academy.monthly.priceId")).thenReturn("price_acad");
    }

    // ─── Item 1: pending downgrade never outlives a deletion / cancel / grace cancel ──────────────

    @Test
    void item1_subscriptionDeleted_voidsPendingChanges() {
        service.handleSubscriptionWebhook("customer.subscription.deleted", SUB_ID, Map.of());

        verify(coachSubscriptionChangeRepository).voidAllPendingForCoach(eq(COACH_ID), any());
        assertThat(coachSub.getTier()).isEqualTo("SCOUT");
        assertThat(marketplace.getTier()).isEqualTo(CoachSubscriptionTier.SCOUT);
    }

    @Test
    void item1_cancelAtPeriodEnd_voidsPendingChanges() throws StripeException {
        when(stripeClient.cancelSubscriptionAtPeriodEnd(SUB_ID)).thenReturn(Instant.now().plus(5, ChronoUnit.DAYS));

        service.cancelCoachSubscription(COACH_ID);

        assertThat(coachSub.isCancelAtPeriodEnd()).isTrue();
        verify(coachSubscriptionChangeRepository).voidAllPendingForCoach(eq(COACH_ID), any());
    }

    @Test
    void item1_applyPendingChanges_cancelledSubscription_voidsChangeWithoutGrantingTier() {
        coachSub.setTier("SCOUT");
        coachSub.setStatus("CANCELLED");
        coachSub.setStripeSubscriptionId(null);
        CoachSubscriptionChange change = pendingChange("INSTRUCTOR");

        service.applyPendingChanges();

        assertThat(change.isApplied()).isTrue();
        assertThat(change.getVoidedAt()).as("voided, not applied").isNotNull();
        assertThat(coachSub.getTier()).isEqualTo("SCOUT");
        verify(coachSubscriptionRepository, never()).save(any());
    }

    @Test
    void item1_applyPendingChanges_pastDue_deferredNeitherAppliedNorVoided() {
        coachSub.setStatus("PAST_DUE");
        CoachSubscriptionChange change = pendingChange("INSTRUCTOR");

        service.applyPendingChanges();

        assertThat(change.isApplied()).isFalse();
        assertThat(change.getVoidedAt()).isNull();
        assertThat(coachSub.getTier()).isEqualTo("ACADEMY");
    }

    // ─── Items 2/10: declined first payment, per-attempt idempotency key ─────────────────────────

    @Test
    void item2_declinedFirstPayment_throwsPaymentDeclined_noTierGranted() throws StripeException {
        givenFreshCoachRow();
        when(stripeClient.createSubscription(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new CardException("declined", "req", "card_declined", null, "generic_decline", null, 402, null));

        assertThatThrownBy(() -> service.subscribeCoach(COACH_ID, COACH_USER_ID, "INSTRUCTOR"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessage("payment.subscription.paymentDeclined");
        assertThat(coachSub.getTier()).isEqualTo("SCOUT");
        verify(coachSubscriptionRepository, never()).save(any());
    }

    @Test
    void item2_nonActiveCreatedSubscription_cancelledNeverGranted() throws StripeException {
        givenFreshCoachRow();
        Subscription incomplete = stripeSub("sub_incomplete", "incomplete");
        when(stripeClient.createSubscription(anyString(), anyString(), anyString(), anyString())).thenReturn(incomplete);

        assertThatThrownBy(() -> service.subscribeCoach(COACH_ID, COACH_USER_ID, "INSTRUCTOR"))
            .hasMessage("payment.subscription.paymentDeclined");
        verify(stripeClient).cancelSubscriptionImmediately("sub_incomplete");
        assertThat(coachSub.getStripeSubscriptionId()).isNull();
    }

    @Test
    void item10_eachSubscribeAttemptUsesAFreshIdempotencyKey() throws StripeException {
        givenFreshCoachRow();
        when(stripeClient.createSubscription(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new CardException("declined", "req", "card_declined", null, null, null, 402, null));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.subscribeCoach(COACH_ID, COACH_USER_ID, "INSTRUCTOR"))
                .isInstanceOf(PaymentGatewayException.class);
        }

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(stripeClient, org.mockito.Mockito.times(2))
            .createSubscription(eq("cus_1"), eq("price_instr"), eq("pm_1"), keys.capture());
        assertThat(keys.getAllValues()).allMatch(k -> k.startsWith("sub-create-"));
        assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
    }

    // ─── Item 3: entitlement follows status ─────────────────────────────────────────────────────

    @Test
    void item3_entitlementHelper_onlyActiveTriallingPastDueKeepPaidTier() {
        assertThat(SubscriptionEntitlement.effectiveCoachTier("ACADEMY", "ACTIVE")).isEqualTo("ACADEMY");
        assertThat(SubscriptionEntitlement.effectiveCoachTier("ACADEMY", "TRIALLING")).isEqualTo("ACADEMY");
        assertThat(SubscriptionEntitlement.effectiveCoachTier("ACADEMY", "PAST_DUE")).isEqualTo("ACADEMY");
        for (String status : List.of("CANCELLED", "UNPAID", "INCOMPLETE", "PAUSED")) {
            assertThat(SubscriptionEntitlement.effectiveCoachTier("ACADEMY", status)).isEqualTo("SCOUT");
            assertThat(SubscriptionEntitlement.effectivePlayerTier("PRO", status)).isEqualTo("ATHLETE");
        }
    }

    @Test
    void item3_reconcile_forcesNonEntitledCoachBackToScout() {
        coachSub.setStatus("UNPAID");
        when(paymentCoachSubscriptionRepository.findAll()).thenReturn(List.of(coachSub));

        service.reconcileMarketplaceTiers();

        assertThat(marketplace.getTier()).isEqualTo(CoachSubscriptionTier.SCOUT);
    }

    @Test
    void item3_subscriptionUpdatedToUnpaid_resyncsMarketplaceToScout() {
        service.handleSubscriptionWebhook("customer.subscription.updated", SUB_ID, Map.of("status", "unpaid"));

        assertThat(coachSub.getStatus()).isEqualTo("UNPAID");
        assertThat(marketplace.getTier()).isEqualTo(CoachSubscriptionTier.SCOUT);
    }

    // ─── Item 4: downgrades change the Stripe price ─────────────────────────────────────────────

    @Test
    void item4_paidDowngrade_movesStripePriceNowWithoutProration_andSchedulesLocalChange() throws StripeException {
        service.changeCoachTier(COACH_ID, "INSTRUCTOR");

        verify(stripeClient).updateSubscriptionTier(eq(SUB_ID), eq("price_instr"), eq(false), startsWith("sub-downgrade-"));
        ArgumentCaptor<CoachSubscriptionChange> saved = ArgumentCaptor.forClass(CoachSubscriptionChange.class);
        verify(coachSubscriptionChangeRepository).save(saved.capture());
        assertThat(saved.getValue().getToTier()).isEqualTo("INSTRUCTOR");
        assertThat(coachSub.getTier()).as("entitled until period end").isEqualTo("ACADEMY");
    }

    @Test
    void item4_downgradeToFreeTier_isCancelAtPeriodEnd() throws StripeException {
        when(stripeClient.cancelSubscriptionAtPeriodEnd(SUB_ID)).thenReturn(Instant.now().plus(5, ChronoUnit.DAYS));

        service.changeCoachTier(COACH_ID, "SCOUT");

        verify(stripeClient).cancelSubscriptionAtPeriodEnd(SUB_ID);
        verify(stripeClient, never()).updateSubscriptionTier(anyString(), anyString(), anyBoolean(), anyString());
        assertThat(coachSub.isCancelAtPeriodEnd()).isTrue();
    }

    @Test
    void item4_applyPendingChanges_syncsStripePriceIdempotentlyPerChange() throws StripeException {
        CoachSubscriptionChange change = pendingChange("INSTRUCTOR");

        service.applyPendingChanges();

        verify(stripeClient).updateSubscriptionTier(SUB_ID, "price_instr", false,
            "sub-downgrade-apply-" + change.getChangeId());
        assertThat(change.isApplied()).isTrue();
        assertThat(coachSub.getTier()).isEqualTo("INSTRUCTOR");
        assertThat(marketplace.getTier()).isEqualTo(CoachSubscriptionTier.INSTRUCTOR);
    }

    @Test
    void item4_applyPendingChanges_stripeFailure_leavesChangePendingForRetry() throws StripeException {
        CoachSubscriptionChange change = pendingChange("INSTRUCTOR");
        when(stripeClient.updateSubscriptionTier(anyString(), anyString(), anyBoolean(), anyString()))
            .thenThrow(new ApiConnectionException("down"));

        service.applyPendingChanges();

        assertThat(change.isApplied()).isFalse();
        assertThat(coachSub.getTier()).isEqualTo("ACADEMY");
    }

    @Test
    void item4_reselectingCurrentTier_restoresPriceAndVoidsPendingDowngrade() throws StripeException {
        when(coachSubscriptionChangeRepository.existsByCoachIdAndAppliedFalseAndVoidedAtIsNull(COACH_ID)).thenReturn(true);

        service.changeCoachTier(COACH_ID, "ACADEMY");

        verify(stripeClient).updateSubscriptionTier(eq(SUB_ID), eq("price_acad"), eq(false), startsWith("sub-restore-"));
        verify(coachSubscriptionChangeRepository).voidAllPendingForCoach(eq(COACH_ID), any());
    }

    // ─── Item 5: tier changes require a healthy subscription ─────────────────────────────────────

    @Test
    void item5_upgradeWhilePastDue_rejectedWithoutStripeCall() throws StripeException {
        coachSub.setTier("INSTRUCTOR");
        coachSub.setStatus("PAST_DUE");

        assertThatThrownBy(() -> service.changeCoachTier(COACH_ID, "ACADEMY"))
            .hasMessage("payment.subscription.notActive");
        verify(stripeClient, never()).updateSubscriptionTier(anyString(), anyString(), anyBoolean(), anyString());
    }

    // ─── Item 6: grace expiry cancels at Stripe; no parallel subscription ────────────────────────

    @Test
    void item6_graceExpiry_cancelsAtStripe_thenRevokesAndClearsId() throws StripeException {
        coachSub.setStatus("PAST_DUE");
        coachSub.setPastDueSince(Instant.now().minus(30, ChronoUnit.DAYS));
        when(configService.getBoundedLong("subscription.pastDue.gracePeriodDays", 0L, 365L)).thenReturn(7L);
        when(paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore(eq("PAST_DUE"), any()))
            .thenReturn(List.of(coachSub));

        service.checkPastDueGracePeriod();

        verify(stripeClient).cancelSubscriptionImmediately(SUB_ID);
        assertThat(coachSub.getStatus()).isEqualTo("CANCELLED");
        assertThat(coachSub.getStripeSubscriptionId()).isNull();
        assertThat(coachSub.getPastDueSince()).isNull();
        verify(coachSubscriptionChangeRepository).voidAllPendingForCoach(eq(COACH_ID), any());
    }

    @Test
    void item6_graceExpiry_stripeCancelFails_rowUntouched() throws StripeException {
        coachSub.setStatus("PAST_DUE");
        coachSub.setPastDueSince(Instant.now().minus(30, ChronoUnit.DAYS));
        when(configService.getBoundedLong("subscription.pastDue.gracePeriodDays", 0L, 365L)).thenReturn(7L);
        when(paymentCoachSubscriptionRepository.findByStatusAndPastDueSinceBefore(eq("PAST_DUE"), any()))
            .thenReturn(List.of(coachSub));
        when(stripeClient.cancelSubscriptionImmediately(SUB_ID)).thenThrow(new ApiConnectionException("down"));

        service.checkPastDueGracePeriod();

        assertThat(coachSub.getStatus()).isEqualTo("PAST_DUE");
        assertThat(coachSub.getStripeSubscriptionId()).isEqualTo(SUB_ID);
    }

    @Test
    void item6_subscribeWhilePastDue_blocked_noSecondStripeSubscription() throws StripeException {
        coachSub.setStatus("PAST_DUE");

        assertThatThrownBy(() -> service.subscribeCoach(COACH_ID, COACH_USER_ID, "INSTRUCTOR"))
            .hasMessage("payment.subscription.updatePaymentMethod");
        verify(stripeClient, never()).createSubscription(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void item6_legacyCancelledRowWithStripeId_cancelsOldSubscriptionBeforeResubscribing() throws StripeException {
        coachSub.setTier("SCOUT");
        coachSub.setStatus("CANCELLED");
        givenStripeCustomer();
        Subscription fresh = stripeSub("sub_new", "active");
        when(stripeClient.createSubscription(anyString(), anyString(), anyString(), anyString())).thenReturn(fresh);

        service.subscribeCoach(COACH_ID, COACH_USER_ID, "INSTRUCTOR");

        verify(stripeClient).cancelSubscriptionImmediately(SUB_ID);
        assertThat(coachSub.getStripeSubscriptionId()).isEqualTo("sub_new");
        assertThat(coachSub.getTier()).isEqualTo("INSTRUCTOR");
    }

    // ─── Item 7: pastDueSince cleared on recovery ───────────────────────────────────────────────

    @Test
    void item7_subscriptionUpdatedActive_clearsPastDueSince() {
        coachSub.setStatus("PAST_DUE");
        coachSub.setPastDueSince(Instant.now().minus(3, ChronoUnit.DAYS));

        service.handleSubscriptionWebhook("customer.subscription.updated", SUB_ID, Map.of("status", "active"));

        assertThat(coachSub.getStatus()).isEqualTo("ACTIVE");
        assertThat(coachSub.getPastDueSince()).isNull();
    }

    @Test
    void item7_invoicePaid_restoresActiveAndClearsPastDueSince() {
        coachSub.setStatus("PAST_DUE");
        coachSub.setPastDueSince(Instant.now().minus(3, ChronoUnit.DAYS));

        service.handleSubscriptionWebhook("invoice.paid", SUB_ID, Map.of());

        assertThat(coachSub.getStatus()).isEqualTo("ACTIVE");
        assertThat(coachSub.getPastDueSince()).isNull();
    }

    // ─── Item 8: ordering + non-entitled statuses ───────────────────────────────────────────────

    @Test
    void item8_olderEventThanLastApplied_ignored() {
        Instant last = Instant.parse("2026-10-01T10:00:00Z");
        coachSub.setLastStripeEventAt(last);

        service.handleSubscriptionWebhook("customer.subscription.updated", SUB_ID,
            Map.of("status", "past_due", "event_created", last.minusSeconds(60).getEpochSecond()));

        assertThat(coachSub.getStatus()).isEqualTo("ACTIVE");
        assertThat(coachSub.getLastStripeEventAt()).isEqualTo(last);
    }

    @Test
    void item8_newerEvent_appliedAndRecorded() {
        Instant created = Instant.parse("2026-10-01T10:00:00Z");

        service.handleSubscriptionWebhook("invoice.payment_failed", SUB_ID,
            Map.of("event_created", created.getEpochSecond()));

        assertThat(coachSub.getStatus()).isEqualTo("PAST_DUE");
        assertThat(coachSub.getPastDueSince()).isNotNull();
        assertThat(coachSub.getLastStripeEventAt()).isEqualTo(created);
    }

    @Test
    void item8_updatedWithIncompleteExpired_treatedAsDeletion() {
        service.handleSubscriptionWebhook("customer.subscription.updated", SUB_ID,
            Map.of("status", "incomplete_expired"));

        assertThat(coachSub.getStatus()).isEqualTo("CANCELLED");
        assertThat(coachSub.getStripeSubscriptionId()).isNull();
        assertThat(coachSub.getTier()).isEqualTo("SCOUT");
    }

    @Test
    void item8_paymentFailedOnUnpaidRow_doesNotReEntitleAsPastDue() {
        coachSub.setStatus("UNPAID");

        service.handleSubscriptionWebhook("invoice.payment_failed", SUB_ID, Map.of());

        assertThat(coachSub.getStatus()).isEqualTo("UNPAID");
    }

    @Test
    void item8_playerDeletion_publishesExpiryAndVoidsChanges() {
        PaymentPlayerSubscription playerSub = new PaymentPlayerSubscription();
        playerSub.setPlayerId(PLAYER_ID);
        playerSub.setTier("PRO");
        playerSub.setBillingInterval("YEARLY");
        playerSub.setStatus("ACTIVE");
        playerSub.setStripeSubscriptionId("sub_player");
        when(paymentPlayerSubscriptionRepository.findByStripeSubscriptionIdForUpdate("sub_player"))
            .thenReturn(Optional.of(playerSub));

        service.handleSubscriptionWebhook("customer.subscription.deleted", "sub_player", Map.of());

        assertThat(playerSub.getTier()).isEqualTo("ATHLETE");
        verify(playerSubscriptionChangeRepository).voidAllPendingForPlayer(eq(PLAYER_ID), any());
        verify(eventPublisher).publishEvent(any(SubscriptionExpiredEvent.class));
    }

    // ─── Item 9: lost updates ───────────────────────────────────────────────────────────────────

    @Test
    void item9_subscriptionEntitiesCarryVersion() throws NoSuchFieldException {
        assertThat(PaymentCoachSubscription.class.getDeclaredField("version").isAnnotationPresent(Version.class)).isTrue();
        assertThat(PaymentPlayerSubscription.class.getDeclaredField("version").isAnnotationPresent(Version.class)).isTrue();
    }

    @Test
    void item9_concurrentSubscribeLostRace_duplicateStripeSubscriptionCancelled() throws StripeException {
        // Pre-Stripe read: a fresh row; by persist time (re-read under lock) another request linked one.
        PaymentCoachSubscription stale = new PaymentCoachSubscription();
        stale.setCoachId(COACH_ID);
        when(paymentCoachSubscriptionRepository.findByCoachId(COACH_ID)).thenReturn(Optional.of(stale));
        givenStripeCustomer();
        Subscription duplicate = stripeSub("sub_dup", "active");
        when(stripeClient.createSubscription(anyString(), anyString(), anyString(), anyString())).thenReturn(duplicate);

        assertThatThrownBy(() -> service.subscribeCoach(COACH_ID, COACH_USER_ID, "INSTRUCTOR"))
            .hasMessage("payment.subscription.alreadyActive");
        verify(stripeClient).cancelSubscriptionImmediately("sub_dup");
        assertThat(coachSub.getStripeSubscriptionId()).isEqualTo(SUB_ID);
    }

    @Test
    void item9_upgradePersist_reReadsUnderLock_supersededBySubscriptionChange() {
        coachSub.setStripeSubscriptionId("sub_other");

        service.persistCoachTierUpgrade(COACH_ID, SUB_ID, "ACADEMY", null);

        verify(paymentCoachSubscriptionRepository, never()).save(any());
    }

    // ─── Item 11: upgrades charge now; failed charge is not granted ──────────────────────────────

    @Test
    void item11_upgrade_chargesProrationImmediately() throws StripeException {
        coachSub.setTier("INSTRUCTOR");
        Subscription updated = stripeSub(SUB_ID, "active");
        when(stripeClient.updateSubscriptionTier(anyString(), anyString(), anyBoolean(), anyString()))
            .thenReturn(updated);

        service.changeCoachTier(COACH_ID, "ACADEMY");

        verify(stripeClient).updateSubscriptionTier(eq(SUB_ID), eq("price_acad"), eq(true), startsWith("sub-upgrade-"));
        assertThat(coachSub.getTier()).isEqualTo("ACADEMY");
    }

    @Test
    void item11_upgradeChargeDeclined_tierNotGranted() throws StripeException {
        coachSub.setTier("INSTRUCTOR");
        marketplace.setTier(CoachSubscriptionTier.INSTRUCTOR);
        when(stripeClient.updateSubscriptionTier(anyString(), anyString(), eq(true), anyString()))
            .thenThrow(new CardException("declined", "req", "card_declined", null, null, null, 402, null));

        assertThatThrownBy(() -> service.changeCoachTier(COACH_ID, "ACADEMY"))
            .hasMessage("payment.subscription.paymentDeclined");
        assertThat(coachSub.getTier()).isEqualTo("INSTRUCTOR");
        assertThat(marketplace.getTier()).isEqualTo(CoachSubscriptionTier.INSTRUCTOR);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private CoachSubscriptionChange pendingChange(String toTier) {
        CoachSubscriptionChange change = new CoachSubscriptionChange();
        change.setChangeId(UUID.randomUUID());
        change.setCoachId(COACH_ID);
        change.setFromTier("ACADEMY");
        change.setToTier(toTier);
        change.setEffectiveAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(coachSubscriptionChangeRepository.findPendingForScheduler(any())).thenReturn(List.of(change));
        when(coachSubscriptionChangeRepository.findById(change.getChangeId())).thenReturn(Optional.of(change));
        return change;
    }

    private void givenFreshCoachRow() {
        coachSub.setTier("SCOUT");
        coachSub.setStripeSubscriptionId(null);
        givenStripeCustomer();
    }

    private void givenStripeCustomer() {
        StripeCustomer customer = new StripeCustomer();
        customer.setParentId(COACH_USER_ID);
        customer.setStripeCustomerId("cus_1");
        customer.setStripePaymentMethodId("pm_1");
        when(stripeCustomerRepository.findById(COACH_USER_ID)).thenReturn(Optional.of(customer));
    }

    private static Subscription stripeSub(String id, String status) {
        Subscription sub = mock(Subscription.class);
        when(sub.getId()).thenReturn(id);
        when(sub.getStatus()).thenReturn(status);
        return sub;
    }
}
