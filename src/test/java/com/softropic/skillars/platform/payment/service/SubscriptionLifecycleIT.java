package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.payment.BasePaymentIT;
import com.softropic.skillars.platform.payment.contract.CoachSubscriptionResponse;
import com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException;
import com.softropic.skillars.platform.payment.repo.CoachSubscriptionChangeRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscription;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionLifecycleIT extends BasePaymentIT {

    @Autowired SubscriptionService subscriptionService;
    @Autowired PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Autowired CoachSubscriptionChangeRepository coachSubscriptionChangeRepository;
    @Autowired ConfigService configService;

    @MockitoBean StripeClient stripeClient;

    private static final long COACH_USER_ID = 91001L;
    private static final String STRIPE_CUSTOMER_ID = "cus_test_coach_91001";
    private static final String STRIPE_SUB_ID = "sub_test_91001";
    private static final String PAYMENT_METHOD = "pm_test_91001";

    private UUID coachId;

    @BeforeEach
    void setUpCoach() throws StripeException {
        coachId = insertTestCoach(COACH_USER_ID, "sub.coach@test.com", "Sub Coach");

        transactionTemplate.execute(status -> {
            // Stripe customer for the coach user — subscribeCoach() resolves the payment
            // method from this row directly (mirrors subscribePlayer's pattern).
            jdbcTemplate.update(
                "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, stripe_payment_method_id) " +
                "VALUES (?, ?, ?) ON CONFLICT (parent_id) DO NOTHING",
                COACH_USER_ID, STRIPE_CUSTOMER_ID, PAYMENT_METHOD
            );

            jdbcTemplate.update(
                "INSERT INTO payment.coach_subscriptions (coach_id) " +
                "VALUES (?) ON CONFLICT (coach_id) DO NOTHING",
                coachId
            );

            // V64 seeds these keys with empty-string placeholders; override with test priceIds.
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, key, value, value_type) " +
                "VALUES (9001, 'subscription.coach.instructor.monthly.priceId', 'price_instructor_monthly', 'STRING') " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"
            );
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, key, value, value_type) " +
                "VALUES (9002, 'subscription.coach.academy.monthly.priceId', 'price_academy_monthly', 'STRING') " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"
            );
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, key, value, value_type) " +
                "VALUES (9003, 'subscription.pastDue.gracePeriodDays', '7', 'LONG') " +
                "ON CONFLICT (key) DO NOTHING"
            );
            return null;
        });

        // Price config keys are not seeded by migrations — force cache reload so ConfigService
        // picks them up from the inserts above before any service call runs.
        configService.invalidate();

        // Default Stripe mock behaviour
        doNothing().when(stripeClient).attachPaymentMethod(anyString(), anyString());
        Subscription mockStripeSub = mock(Subscription.class);
        when(mockStripeSub.getId()).thenReturn(STRIPE_SUB_ID);
        when(mockStripeSub.getStatus()).thenReturn("active");
        when(stripeClient.createSubscription(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(mockStripeSub);
        when(stripeClient.updateSubscriptionTier(anyString(), anyString(), anyBoolean(), anyString()))
            .thenReturn(mockStripeSub);
        when(stripeClient.cancelSubscriptionAtPeriodEnd(anyString()))
            .thenReturn(Instant.now().plus(30, ChronoUnit.DAYS));
    }

    @AfterEach
    void cleanSubscriptionData() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("DELETE FROM payment.coach_subscription_changes");
            jdbcTemplate.execute("DELETE FROM payment.player_subscription_changes");
            jdbcTemplate.execute("DELETE FROM payment.coach_subscriptions");
            jdbcTemplate.execute("DELETE FROM payment.player_subscriptions");
            jdbcTemplate.execute("DELETE FROM marketplace.coach_subscriptions");
            // Restore V64 placeholder values — DO NOT delete (the rows were upserted, not inserted)
            jdbcTemplate.update(
                "UPDATE main.platform_config SET value = '' WHERE key = 'subscription.coach.instructor.monthly.priceId'"
            );
            jdbcTemplate.update(
                "UPDATE main.platform_config SET value = '' WHERE key = 'subscription.coach.academy.monthly.priceId'"
            );
            jdbcTemplate.update(
                "DELETE FROM payment.stripe_customers WHERE parent_id = ?", COACH_USER_ID
            );
            return null;
        });
        configService.invalidate();
    }

    // ─── Subscribe ────────────────────────────────────────────────────────────────

    @Test
    void coachSubscribe_createsActiveSubscription() throws StripeException {
        CoachSubscriptionResponse resp = subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");

        assertThat(resp.tier()).isEqualTo("INSTRUCTOR");
        assertThat(resp.status()).isEqualTo("ACTIVE");
        assertThat(resp.subscriptionId()).isNotNull();

        // Verify Stripe SDK was called
        verify(stripeClient, times(1))
            .createSubscription(eq(STRIPE_CUSTOMER_ID), eq("price_instructor_monthly"), eq(PAYMENT_METHOD),
                startsWith("sub-create-"));
        // The saved card was already attached to this customer when it was saved via
        // /setup-intent + /save-payment-method — re-attaching here would throw on Stripe's side.
        verify(stripeClient, never()).attachPaymentMethod(anyString(), anyString());

        // Verify DB row
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStripeSubscriptionId()).isEqualTo(STRIPE_SUB_ID);
        assertThat(sub.getTier()).isEqualTo("INSTRUCTOR");
    }

    @Test
    void coachSubscribe_noSavedCard_throwsNoPaymentMethod() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "DELETE FROM payment.stripe_customers WHERE parent_id = ?", COACH_USER_ID
            );
            return null;
        });

        assertThatThrownBy(() ->
            subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR")
        ).isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.noPaymentMethod");
    }

    @Test
    void coachSubscribe_cardSetupIncomplete_throwsNoPaymentMethod() {
        // The StripeCustomer row exists (POST /setup-intent was called) but stripe_payment_method_id
        // is still null — the coach opened the "add card" dialog and abandoned it before
        // POST /save-payment-method completed. Distinct from the no-row-at-all case above.
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "UPDATE payment.stripe_customers SET stripe_payment_method_id = NULL WHERE parent_id = ?",
                COACH_USER_ID
            );
            return null;
        });

        assertThatThrownBy(() ->
            subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR")
        ).isInstanceOf(PaymentGatewayException.class)
            .hasMessageContaining("payment.noPaymentMethod");
    }

    @Test
    void coachSubscribe_alreadyActive_throws409() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");

        assertThatThrownBy(() ->
            subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR")
        ).isInstanceOf(PaymentGatewayException.class);
    }

    // ─── Upgrade ─────────────────────────────────────────────────────────────────

    @Test
    void coachUpgrade_immediatelyApplied() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");

        subscriptionService.changeCoachTier(coachId, "ACADEMY");

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("ACADEMY");

        // fix/payment-subscription-entitlements: upgrades charge the proration immediately.
        verify(stripeClient, times(1))
            .updateSubscriptionTier(eq(STRIPE_SUB_ID), eq("price_academy_monthly"), eq(true), startsWith("sub-upgrade-"));
    }

    // ─── Downgrade Scheduled ─────────────────────────────────────────────────────

    @Test
    void coachDowngrade_scheduledPendingChange() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "ACADEMY");

        subscriptionService.changeCoachTier(coachId, "INSTRUCTOR");

        // Tier in payment table must not change yet
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("ACADEMY");

        // A pending change must be queued
        long pendingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.coach_subscription_changes " +
            "WHERE coach_id = ? AND applied = false AND voided_at IS NULL",
            Long.class, coachId
        );
        assertThat(pendingCount).isEqualTo(1L);
    }

    @Test
    void applyPendingChanges_applicator_appliesExpiredDowngrade() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "ACADEMY");
        subscriptionService.changeCoachTier(coachId, "INSTRUCTOR");

        forcePendingCoachChangeDue();

        subscriptionService.applyPendingChanges();

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("INSTRUCTOR");
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");

        long pendingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.coach_subscription_changes " +
            "WHERE coach_id = ? AND applied = false AND voided_at IS NULL",
            Long.class, coachId
        );
        assertThat(pendingCount).isZero();
    }

    @Test
    void coachDowngrade_movesStripePriceNowWithoutProration() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "ACADEMY");

        subscriptionService.changeCoachTier(coachId, "INSTRUCTOR");

        verify(stripeClient).updateSubscriptionTier(eq(STRIPE_SUB_ID), eq("price_instructor_monthly"), eq(false),
            startsWith("sub-downgrade-"));
    }

    // ─── fix/payment-subscription-entitlements item 1: no downgrade after deletion ───

    @Test
    void scheduledDowngrade_voidedBySubscriptionDeletion_neverRegrantsPaidTier() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "ACADEMY");
        subscriptionService.changeCoachTier(coachId, "INSTRUCTOR");
        forcePendingCoachChangeDue();

        subscriptionService.handleSubscriptionWebhook("customer.subscription.deleted", STRIPE_SUB_ID, Map.of());
        subscriptionService.applyPendingChanges();

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("SCOUT");
        assertThat(sub.getStatus()).isEqualTo("CANCELLED");
        assertThat(marketplaceTier()).isEqualTo("SCOUT");
        assertThat(pendingCoachChangeCount()).isZero();
    }

    @Test
    void scheduledDowngrade_subscriptionNoLongerActive_voidedNotApplied() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "ACADEMY");
        subscriptionService.changeCoachTier(coachId, "INSTRUCTOR");
        forcePendingCoachChangeDue();
        // Status moved to a non-entitled value by a webhook the old code would have ignored.
        subscriptionService.handleSubscriptionWebhook("customer.subscription.updated", STRIPE_SUB_ID,
            Map.of("status", "unpaid"));

        subscriptionService.applyPendingChanges();

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo("UNPAID");
        assertThat(sub.getTier()).as("change voided, stored tier untouched").isEqualTo("ACADEMY");
        assertThat(pendingCoachChangeCount()).isZero();
        assertThat(marketplaceTier()).as("UNPAID is not entitled").isEqualTo("SCOUT");
    }

    // ─── fix/payment-subscription-entitlements items 2/3: declined payment never grants ───

    @Test
    void coachSubscribe_declinedFirstPayment_noTierGranted() throws StripeException {
        when(stripeClient.createSubscription(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new com.stripe.exception.CardException("Your card was declined.", "req_1",
                "card_declined", null, "generic_decline", null, 402, null));

        assertThatThrownBy(() -> subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessage("payment.subscription.paymentDeclined");

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("SCOUT");
        assertThat(sub.getStripeSubscriptionId()).isNull();
        assertThat(marketplaceTier()).isNotEqualTo("INSTRUCTOR");
    }

    @Test
    void coachSubscribe_incompleteSubscription_cancelledAndNotGranted() throws StripeException {
        Subscription incomplete = mock(Subscription.class);
        when(incomplete.getId()).thenReturn("sub_incomplete_91001");
        when(incomplete.getStatus()).thenReturn("incomplete");
        when(stripeClient.createSubscription(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(incomplete);

        assertThatThrownBy(() -> subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessage("payment.subscription.paymentDeclined");

        verify(stripeClient).cancelSubscriptionImmediately("sub_incomplete_91001");
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("SCOUT");
        assertThat(sub.getStripeSubscriptionId()).isNull();
    }

    @Test
    void unpaidThenInvoicePaid_entitlementRevokedThenRestored() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");
        assertThat(marketplaceTier()).isEqualTo("INSTRUCTOR");

        subscriptionService.handleSubscriptionWebhook("customer.subscription.updated", STRIPE_SUB_ID,
            Map.of("status", "unpaid"));
        assertThat(marketplaceTier()).isEqualTo("SCOUT");

        subscriptionService.handleSubscriptionWebhook("invoice.paid", STRIPE_SUB_ID, Map.of());
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");
        assertThat(sub.getPastDueSince()).isNull();
        assertThat(marketplaceTier()).isEqualTo("INSTRUCTOR");
    }

    // ─── fix/payment-subscription-entitlements item 6: grace expiry cancels at Stripe ───

    @Test
    void graceExpiry_cancelsStripeSubscriptionAndAllowsCleanResubscribe() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");
        subscriptionService.handleSubscriptionWebhook("invoice.payment_failed", STRIPE_SUB_ID, Map.of());

        // While PAST_DUE a second subscribe is blocked (it would bill in parallel).
        assertThatThrownBy(() -> subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "ACADEMY"))
            .isInstanceOf(PaymentGatewayException.class)
            .hasMessage("payment.subscription.updatePaymentMethod");

        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE payment.coach_subscriptions SET past_due_since = ? WHERE coach_id = ?",
            java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), coachId));

        subscriptionService.checkPastDueGracePeriod();

        verify(stripeClient).cancelSubscriptionImmediately(STRIPE_SUB_ID);
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("SCOUT");
        assertThat(sub.getStatus()).isEqualTo("CANCELLED");
        assertThat(sub.getStripeSubscriptionId()).isNull();
        assertThat(sub.getPastDueSince()).isNull();
        assertThat(marketplaceTier()).isEqualTo("SCOUT");

        // A late Stripe event for the cancelled subscription no longer matches any row.
        subscriptionService.handleSubscriptionWebhook("invoice.paid", STRIPE_SUB_ID, Map.of());
        assertThat(paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow().getStatus())
            .isEqualTo("CANCELLED");

        CoachSubscriptionResponse resp = subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");
        assertThat(resp.status()).isEqualTo("ACTIVE");
    }

    @Test
    void graceExpiry_stripeCancelFails_rowLeftPastDueForRetry() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");
        subscriptionService.handleSubscriptionWebhook("invoice.payment_failed", STRIPE_SUB_ID, Map.of());
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE payment.coach_subscriptions SET past_due_since = ? WHERE coach_id = ?",
            java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), coachId));
        when(stripeClient.cancelSubscriptionImmediately(STRIPE_SUB_ID))
            .thenThrow(new com.stripe.exception.ApiConnectionException("stripe down"));

        subscriptionService.checkPastDueGracePeriod();

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo("PAST_DUE");
        assertThat(sub.getStripeSubscriptionId()).isEqualTo(STRIPE_SUB_ID);
    }

    // ─── fix/payment-subscription-payment-method-sync ───────────────────────────

    @Test
    void unpaidPastGrace_cancelledAtStripeAndLinkCleared() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");
        subscriptionService.handleSubscriptionWebhook("customer.subscription.updated", STRIPE_SUB_ID,
            Map.of("status", "unpaid"));
        PaymentCoachSubscription unpaid = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(unpaid.getPastDueSince()).as("UNPAID starts the grace clock").isNotNull();

        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE payment.coach_subscriptions SET past_due_since = ? WHERE coach_id = ?",
            java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), coachId));

        subscriptionService.checkPastDueGracePeriod();

        verify(stripeClient).cancelSubscriptionImmediately(STRIPE_SUB_ID);
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository.findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo("CANCELLED");
        assertThat(sub.getStripeSubscriptionId()).isNull();
        assertThat(marketplaceTier()).isEqualTo("SCOUT");
    }

    @Test
    void newCardSaved_pastDueSubscriptionMovedToCardAndInvoiceRetried() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");
        subscriptionService.handleSubscriptionWebhook("invoice.payment_failed", STRIPE_SUB_ID, Map.of());

        subscriptionService.syncPaymentMethodToSubscriptions(COACH_USER_ID, "pm_replacement");

        verify(stripeClient).updateSubscriptionDefaultPaymentMethod(STRIPE_SUB_ID, "pm_replacement");
        verify(stripeClient).payLatestOpenInvoice(eq(STRIPE_SUB_ID), eq("pm_replacement"), startsWith("inv-retry-"));
    }

    // ─── Cancel ──────────────────────────────────────────────────────────────────

    @Test
    void coachCancel_setsCancelAtPeriodEnd() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");

        subscriptionService.cancelCoachSubscription(coachId);

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.isCancelAtPeriodEnd()).isTrue();

        verify(stripeClient, times(1)).cancelSubscriptionAtPeriodEnd(STRIPE_SUB_ID);
    }

    // ─── Webhook: subscription.updated ───────────────────────────────────────────

    @Test
    void webhookSubscriptionUpdated_syncsStatusAndPeriodEnd() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");
        Instant newPeriodEnd = Instant.now().plus(60, ChronoUnit.DAYS);

        subscriptionService.handleSubscriptionWebhook(
            "customer.subscription.updated",
            STRIPE_SUB_ID,
            Map.of(
                "status", "active",
                "current_period_end", newPeriodEnd.getEpochSecond(),
                "cancel_at_period_end", false
            )
        );

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo("ACTIVE");
        assertThat(sub.getCurrentPeriodEnd()).isCloseTo(newPeriodEnd, within(2));
    }

    // ─── Webhook: invoice.payment_failed ─────────────────────────────────────────

    @Test
    void webhookInvoicePaymentFailed_setsPastDue_onlyOnFirstFailure() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");

        subscriptionService.handleSubscriptionWebhook(
            "invoice.payment_failed", STRIPE_SUB_ID, Map.of()
        );

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo("PAST_DUE");
        Instant firstPastDueSince = sub.getPastDueSince();
        assertThat(firstPastDueSince).isNotNull();

        // Second failure must NOT overwrite pastDueSince
        subscriptionService.handleSubscriptionWebhook(
            "invoice.payment_failed", STRIPE_SUB_ID, Map.of()
        );

        PaymentCoachSubscription subAfterSecond = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(subAfterSecond.getPastDueSince()).isEqualTo(firstPastDueSince);
    }

    // ─── Webhook idempotency ──────────────────────────────────────────────────────

    @Test
    void duplicateWebhookEvent_processedOnlyOnce() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");

        Instant periodEnd = Instant.now().plus(30, ChronoUnit.DAYS);
        Map<String, Object> data = Map.of(
            "status", "past_due",
            "current_period_end", periodEnd.getEpochSecond(),
            "cancel_at_period_end", false
        );

        // Simulate the StripeWebhookService idempotency check by calling handleSubscriptionWebhook twice
        subscriptionService.handleSubscriptionWebhook("customer.subscription.updated", STRIPE_SUB_ID, data);
        subscriptionService.handleSubscriptionWebhook("customer.subscription.updated", STRIPE_SUB_ID, data);

        // Status should be PAST_DUE from the first call; the second should not cause issues
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStatus()).isEqualTo("PAST_DUE");
    }

    // ─── Webhook: subscription.deleted ───────────────────────────────────────────

    @Test
    void webhookSubscriptionDeleted_coachDowngradedToScout() throws StripeException {
        subscriptionService.subscribeCoach(coachId, COACH_USER_ID, "INSTRUCTOR");

        subscriptionService.handleSubscriptionWebhook(
            "customer.subscription.deleted", STRIPE_SUB_ID, Map.of()
        );

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("SCOUT");
        assertThat(sub.getStatus()).isEqualTo("CANCELLED");
        assertThat(sub.getStripeSubscriptionId()).isNull();
    }

    // ─── Player Subscribe (Deferred-11 AC 5) ──────────────────────────────────────
    // subscribePlayer() no longer takes a caller-supplied paymentMethodId — it resolves the
    // saved card from the parent's StripeCustomer row, and must never call attachPaymentMethod
    // (the saved card is already attached to that customer from the setup-intent flow; calling
    // attach again throws on Stripe's side).

    private static final long PLAYER_PARENT_ID = 91101L;
    private static final long PLAYER_ID = 91102L;
    private static final String PLAYER_PARENT_STRIPE_CUSTOMER_ID = "cus_test_parent_91101";
    private static final String PLAYER_PARENT_PAYMENT_METHOD = "pm_test_parent_91101";

    private void seedPlayerPriceConfig() {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.platform_config (id, key, value, value_type) " +
                "VALUES (9010, 'subscription.player.semi_pro.yearly.priceId', 'price_semi_pro_yearly', 'STRING') " +
                "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value"
            );
            return null;
        });
        configService.invalidate();
    }

    private void linkParentToPlayer(long parentId, long playerId) {
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO main.parent_player_links " +
                "(id, parent_id, player_id, consent_accepted_at, consent_policy_version) " +
                "VALUES (?, ?, ?, ?, '1.0') ON CONFLICT (player_id) DO NOTHING",
                playerId, parentId, playerId, java.sql.Timestamp.from(Instant.now())
            );
            return null;
        });
    }

    @Test
    void playerSubscribe_resolvesPaymentMethodFromStripeCustomer_neverAttachesPaymentMethod() throws StripeException {
        insertTestParent(PLAYER_PARENT_ID, "player.sub.parent@test.com");
        insertTestPlayer(PLAYER_ID, PLAYER_PARENT_ID);
        linkParentToPlayer(PLAYER_PARENT_ID, PLAYER_ID);
        seedPlayerPriceConfig();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, stripe_payment_method_id) " +
                "VALUES (?, ?, ?) ON CONFLICT (parent_id) DO UPDATE SET " +
                "stripe_customer_id = EXCLUDED.stripe_customer_id, " +
                "stripe_payment_method_id = EXCLUDED.stripe_payment_method_id",
                PLAYER_PARENT_ID, PLAYER_PARENT_STRIPE_CUSTOMER_ID, PLAYER_PARENT_PAYMENT_METHOD
            );
            return null;
        });

        subscriptionService.subscribePlayer(PLAYER_PARENT_ID, PLAYER_ID, "SEMI_PRO", "YEARLY");

        verify(stripeClient, times(1)).createSubscription(
            eq(PLAYER_PARENT_STRIPE_CUSTOMER_ID), eq("price_semi_pro_yearly"), eq(PLAYER_PARENT_PAYMENT_METHOD),
            startsWith("sub-create-"));
        verify(stripeClient, never()).attachPaymentMethod(anyString(), anyString());
    }

    @Test
    void playerSubscribe_noStripeCustomerRow_throwsNoPaymentMethod() {
        insertTestParent(PLAYER_PARENT_ID, "player.sub.parent2@test.com");
        insertTestPlayer(PLAYER_ID + 1, PLAYER_PARENT_ID);
        linkParentToPlayer(PLAYER_PARENT_ID, PLAYER_ID + 1);
        seedPlayerPriceConfig();

        assertThatThrownBy(() ->
            subscriptionService.subscribePlayer(PLAYER_PARENT_ID, PLAYER_ID + 1, "SEMI_PRO", "YEARLY")
        ).isInstanceOf(PaymentGatewayException.class).hasMessage("payment.noPaymentMethod");
    }

    @Test
    void playerSubscribe_stripeCustomerRowWithNullPaymentMethod_throwsNoPaymentMethod() {
        insertTestParent(PLAYER_PARENT_ID, "player.sub.parent3@test.com");
        insertTestPlayer(PLAYER_ID + 2, PLAYER_PARENT_ID);
        linkParentToPlayer(PLAYER_PARENT_ID, PLAYER_ID + 2);
        seedPlayerPriceConfig();
        transactionTemplate.execute(status -> {
            jdbcTemplate.update(
                "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id, stripe_payment_method_id) " +
                "VALUES (?, ?, NULL) ON CONFLICT (parent_id) DO UPDATE SET " +
                "stripe_customer_id = EXCLUDED.stripe_customer_id, stripe_payment_method_id = NULL",
                PLAYER_PARENT_ID, PLAYER_PARENT_STRIPE_CUSTOMER_ID
            );
            return null;
        });

        assertThatThrownBy(() ->
            subscriptionService.subscribePlayer(PLAYER_PARENT_ID, PLAYER_ID + 2, "SEMI_PRO", "YEARLY")
        ).isInstanceOf(PaymentGatewayException.class).hasMessage("payment.noPaymentMethod");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private void forcePendingCoachChangeDue() {
        transactionTemplate.execute(status -> jdbcTemplate.update(
            "UPDATE payment.coach_subscription_changes SET effective_at = ? " +
            "WHERE coach_id = ? AND applied = false AND voided_at IS NULL",
            java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), coachId));
    }

    private long pendingCoachChangeCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment.coach_subscription_changes " +
            "WHERE coach_id = ? AND applied = false AND voided_at IS NULL",
            Long.class, coachId);
    }

    private String marketplaceTier() {
        return jdbcTemplate.query("SELECT tier FROM marketplace.coach_subscriptions WHERE coach_id = ?",
            rs -> rs.next() ? rs.getString(1) : null, coachId);
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long seconds) {
        return org.assertj.core.api.Assertions.within(seconds, ChronoUnit.SECONDS);
    }
}
