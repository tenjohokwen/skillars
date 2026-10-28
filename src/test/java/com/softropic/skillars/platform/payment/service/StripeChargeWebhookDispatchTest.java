package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccount;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.repo.StripeWebhookEventRepository;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix/payment-payouts-disputes-webhooks items 1 + 5: {@code StripeWebhookService} routes the charge
 * dispute / refund events to {@link ChargeDisputeWebhookService}, and applies {@code account.updated}
 * only when it is not older than the snapshot already stored (plus stores the transfers capability).
 */
@ExtendWith(MockitoExtension.class)
class StripeChargeWebhookDispatchTest {

    @Mock CoachStripeAccountRepository coachStripeAccountRepository;
    @Mock StripeWebhookEventRepository webhookEventRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock CoachProfileRepository coachProfileRepository;
    @Mock ChargeDisputeWebhookService chargeDisputeWebhookService;

    private StripeWebhookService webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new StripeWebhookService(
            coachStripeAccountRepository, webhookEventRepository, new PaymentProperties(), eventPublisher,
            paymentCoachSubscriptionRepository, paymentPlayerSubscriptionRepository,
            stripeCustomerRepository, coachProfileRepository, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(webhookService, "chargeDisputeWebhookService", chargeDisputeWebhookService);
        webhookService.initializeCounters();
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
    }

    private static Event event(String id, String type, long created, String object) {
        return ApiResource.GSON.fromJson("""
            {"id": "%s", "object": "event", "type": "%s", "created": %d, "api_version": "%s",
             "data": {"object": %s}}
            """.formatted(id, type, created, com.stripe.Stripe.API_VERSION, object), Event.class);
    }

    @Test
    void disputeCreated_isRoutedWithTheDispute() {
        webhookService.handleEventAtomically(event("evt_1", "charge.dispute.created", 1L,
            "{\"id\": \"dp_1\", \"object\": \"dispute\", \"payment_intent\": \"pi_1\", \"amount\": 5000}"));

        ArgumentCaptor<Dispute> d = ArgumentCaptor.forClass(Dispute.class);
        verify(chargeDisputeWebhookService).onDisputeCreated(d.capture());
        assertThat(d.getValue().getId()).isEqualTo("dp_1");
        assertThat(d.getValue().getPaymentIntent()).isEqualTo("pi_1");
    }

    @Test
    void disputeClosed_isRouted() {
        webhookService.handleEventAtomically(event("evt_2", "charge.dispute.closed", 1L,
            "{\"id\": \"dp_1\", \"object\": \"dispute\", \"status\": \"lost\"}"));

        verify(chargeDisputeWebhookService).onDisputeClosed(any(Dispute.class));
    }

    @Test
    void chargeRefunded_isRoutedWithTheEvent() {
        Event e = event("evt_3", "charge.refunded", 1L, "{\"id\": \"ch_1\", \"object\": \"charge\"}");
        webhookService.handleEventAtomically(e);

        verify(chargeDisputeWebhookService).onChargeRefunded(any(Charge.class), eq(e));
    }

    private CoachStripeAccount account(Instant lastEvent) {
        CoachStripeAccount a = new CoachStripeAccount();
        a.setCoachId(UUID.randomUUID());
        a.setStripeAccountId("acct_1");
        a.setOnboardingStatus("COMPLETE");
        a.setChargesEnabled(true);
        a.setPayoutsEnabled(true);
        a.setLastEventCreatedAt(lastEvent);
        return a;
    }

    private static String accountJson(boolean enabled, String transfers) {
        return """
            {"id": "acct_1", "object": "account", "charges_enabled": %b, "payouts_enabled": %b,
             "capabilities": {"transfers": "%s"}}
            """.formatted(enabled, enabled, transfers);
    }

    @Test
    void accountUpdated_olderThanTheLastApplied_isIgnored() {
        CoachStripeAccount a = account(Instant.ofEpochSecond(2_000));
        when(coachStripeAccountRepository.findByStripeAccountId("acct_1")).thenReturn(Optional.of(a));

        webhookService.handleEventAtomically(event("evt_4", "account.updated", 1_000, accountJson(false, "inactive")));

        assertThat(a.getOnboardingStatus()).isEqualTo("COMPLETE");
        assertThat(a.isChargesEnabled()).isTrue();
        assertThat(a.getTransfersCapability()).isNull();
        assertThat(a.getLastEventCreatedAt()).isEqualTo(Instant.ofEpochSecond(2_000));
    }

    @Test
    void accountUpdated_newer_isApplied_storesCapabilityAndCreated() {
        CoachStripeAccount a = account(Instant.ofEpochSecond(1_000));
        when(coachStripeAccountRepository.findByStripeAccountId("acct_1")).thenReturn(Optional.of(a));

        webhookService.handleEventAtomically(event("evt_5", "account.updated", 2_000, accountJson(false, "inactive")));

        assertThat(a.getOnboardingStatus()).isEqualTo("RESTRICTED");
        assertThat(a.getTransfersCapability()).isEqualTo("inactive");
        assertThat(a.getLastEventCreatedAt()).isEqualTo(Instant.ofEpochSecond(2_000));
    }
}
