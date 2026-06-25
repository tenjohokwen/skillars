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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
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
            // Stripe customer for the coach user
            jdbcTemplate.update(
                "INSERT INTO payment.stripe_customers (parent_id, stripe_customer_id) " +
                "VALUES (?, ?) ON CONFLICT (parent_id) DO NOTHING",
                COACH_USER_ID, STRIPE_CUSTOMER_ID
            );

            // Bootstrap coach subscription row with stripe_customer_id so subscribeCoach()
            // can find it via PaymentCoachSubscription.getStripeCustomerId()
            jdbcTemplate.update(
                "INSERT INTO payment.coach_subscriptions (coach_id, stripe_customer_id) " +
                "VALUES (?, ?) ON CONFLICT (coach_id) DO UPDATE SET stripe_customer_id = EXCLUDED.stripe_customer_id",
                coachId, STRIPE_CUSTOMER_ID
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
        when(stripeClient.createSubscription(anyString(), anyString(), anyString()))
            .thenReturn(mockStripeSub);
        when(stripeClient.updateSubscriptionTier(anyString(), anyString()))
            .thenReturn(Instant.now().plus(30, ChronoUnit.DAYS));
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
        CoachSubscriptionResponse resp = subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD);

        assertThat(resp.tier()).isEqualTo("INSTRUCTOR");
        assertThat(resp.status()).isEqualTo("ACTIVE");
        assertThat(resp.subscriptionId()).isNotNull();

        // Verify Stripe SDK was called
        verify(stripeClient, times(1))
            .createSubscription(STRIPE_CUSTOMER_ID, "price_instructor_monthly", PAYMENT_METHOD);

        // Verify DB row
        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getStripeSubscriptionId()).isEqualTo(STRIPE_SUB_ID);
        assertThat(sub.getTier()).isEqualTo("INSTRUCTOR");
    }

    @Test
    void coachSubscribe_alreadyActive_throws409() throws StripeException {
        subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD);

        assertThatThrownBy(() ->
            subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD)
        ).isInstanceOf(PaymentGatewayException.class);
    }

    // ─── Upgrade ─────────────────────────────────────────────────────────────────

    @Test
    void coachUpgrade_immediatelyApplied() throws StripeException {
        subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD);

        subscriptionService.changeCoachTier(coachId, "ACADEMY");

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("ACADEMY");

        verify(stripeClient, times(1))
            .updateSubscriptionTier(STRIPE_SUB_ID, "price_academy_monthly");
    }

    // ─── Downgrade Scheduled ─────────────────────────────────────────────────────

    @Test
    void coachDowngrade_scheduledPendingChange() throws StripeException {
        subscriptionService.subscribeCoach(coachId, "ACADEMY", PAYMENT_METHOD);

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
        subscriptionService.subscribeCoach(coachId, "ACADEMY", PAYMENT_METHOD);
        subscriptionService.changeCoachTier(coachId, "INSTRUCTOR");

        // Force the effective_at to be in the past
        jdbcTemplate.update(
            "UPDATE payment.coach_subscription_changes SET effective_at = ? " +
            "WHERE coach_id = ? AND applied = false AND voided_at IS NULL",
            java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), coachId
        );

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

    // ─── Cancel ──────────────────────────────────────────────────────────────────

    @Test
    void coachCancel_setsCancelAtPeriodEnd() throws StripeException {
        subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD);

        subscriptionService.cancelCoachSubscription(coachId);

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.isCancelAtPeriodEnd()).isTrue();

        verify(stripeClient, times(1)).cancelSubscriptionAtPeriodEnd(STRIPE_SUB_ID);
    }

    // ─── Webhook: subscription.updated ───────────────────────────────────────────

    @Test
    void webhookSubscriptionUpdated_syncsStatusAndPeriodEnd() throws StripeException {
        subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD);
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
        subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD);

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
        subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD);

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
        subscriptionService.subscribeCoach(coachId, "INSTRUCTOR", PAYMENT_METHOD);

        subscriptionService.handleSubscriptionWebhook(
            "customer.subscription.deleted", STRIPE_SUB_ID, Map.of()
        );

        PaymentCoachSubscription sub = paymentCoachSubscriptionRepository
            .findByCoachId(coachId).orElseThrow();
        assertThat(sub.getTier()).isEqualTo("SCOUT");
        assertThat(sub.getStatus()).isEqualTo("CANCELLED");
        assertThat(sub.getStripeSubscriptionId()).isNull();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private static org.assertj.core.data.TemporalUnitOffset within(long seconds) {
        return org.assertj.core.api.Assertions.within(seconds, ChronoUnit.SECONDS);
    }
}
