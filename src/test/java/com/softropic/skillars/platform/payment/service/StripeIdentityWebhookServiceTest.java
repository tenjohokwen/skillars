package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.platform.payment.config.PaymentProperties;
import com.softropic.skillars.platform.payment.repo.CoachStripeAccountRepository;
import com.softropic.skillars.platform.payment.repo.PaymentCoachSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.PaymentPlayerSubscriptionRepository;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.repo.StripeWebhookEventRepository;
import com.softropic.skillars.platform.security.contract.AgeVerificationStatus;
import com.softropic.skillars.platform.security.service.AgeVerificationService;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.Event;
import com.stripe.model.identity.VerificationSession;
import com.stripe.net.ApiResource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S-27: {@code identity.verification_session.*} events reach {@link AgeVerificationService#applyOutcome} through the
 * platform webhook's idempotent dispatch, and a verified event reads the date of birth from the expanded session.
 */
@ExtendWith(MockitoExtension.class)
class StripeIdentityWebhookServiceTest {

    @Mock StripeClient stripeClient;
    @Mock AgeVerificationService ageVerificationService;
    @Mock CoachStripeAccountRepository coachStripeAccountRepository;
    @Mock StripeWebhookEventRepository webhookEventRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PaymentCoachSubscriptionRepository paymentCoachSubscriptionRepository;
    @Mock PaymentPlayerSubscriptionRepository paymentPlayerSubscriptionRepository;
    @Mock StripeCustomerRepository stripeCustomerRepository;
    @Mock CoachProfileRepository coachProfileRepository;

    private StripeWebhookService webhookService;

    @BeforeEach
    void setUp() {
        StripeIdentityWebhookService identity = new StripeIdentityWebhookService(stripeClient, ageVerificationService);
        webhookService = new StripeWebhookService(
            coachStripeAccountRepository, webhookEventRepository, new PaymentProperties(), eventPublisher,
            paymentCoachSubscriptionRepository, paymentPlayerSubscriptionRepository,
            stripeCustomerRepository, coachProfileRepository, new SimpleMeterRegistry(),
            new CoachStripeAccountStatusApplier(eventPublisher));
        ReflectionTestUtils.setField(webhookService, "identityWebhookService", identity);
        webhookService.initializeCounters();
        when(webhookEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
    }

    private static Event event(String type, String sessionId) {
        return ApiResource.GSON.fromJson("""
            {"id": "evt_%s", "object": "event", "type": "%s", "created": 1, "api_version": "%s",
             "data": {"object": {"id": "%s", "object": "identity.verification_session", "status": "verified"}}}
            """.formatted(type.hashCode(), type, com.stripe.Stripe.API_VERSION, sessionId), Event.class);
    }

    private static VerificationSession sessionWithDob(Long y, Long m, Long d) {
        return ApiResource.GSON.fromJson("""
            {"id": "vs_1", "object": "identity.verification_session", "status": "verified",
             "verified_outputs": {"dob": {"year": %s, "month": %s, "day": %s}}}
            """.formatted(y, m, d), VerificationSession.class);
    }

    @Test
    void verified_readsTheDobFromTheExpandedSession_andAppliesIt() throws Exception {
        when(stripeClient.retrieveVerificationSessionWithOutputs("vs_1")).thenReturn(sessionWithDob(1991L, 7L, 9L));

        webhookService.handleEventAtomically(event("identity.verification_session.verified", "vs_1"));

        verify(ageVerificationService).applyOutcome("vs_1", AgeVerificationStatus.VERIFIED, LocalDate.of(1991, 7, 9));
    }

    @Test
    void verified_withoutVerifiedOutputs_passesANullDobSoTheServiceFailsAndStripeRetries() throws Exception {
        VerificationSession bare = ApiResource.GSON.fromJson(
            "{\"id\": \"vs_1\", \"object\": \"identity.verification_session\"}", VerificationSession.class);
        when(stripeClient.retrieveVerificationSessionWithOutputs("vs_1")).thenReturn(bare);

        webhookService.handleEventAtomically(event("identity.verification_session.verified", "vs_1"));

        verify(ageVerificationService).applyOutcome("vs_1", AgeVerificationStatus.VERIFIED, null);
    }

    @Test
    void verified_whenStripeCannotBeReached_throwsSoTheWebhookAnswers5xx() throws Exception {
        when(stripeClient.retrieveVerificationSessionWithOutputs("vs_1"))
            .thenThrow(new ApiConnectionException("boom"));

        assertThatThrownBy(() ->
            webhookService.handleEventAtomically(event("identity.verification_session.verified", "vs_1")))
            .isInstanceOf(IllegalStateException.class);
        verify(ageVerificationService, never()).applyOutcome(any(), any(), any());
    }

    @Test
    void otherOutcomes_areMappedWithoutCallingStripe() {
        webhookService.handleEventAtomically(event("identity.verification_session.requires_input", "vs_1"));
        webhookService.handleEventAtomically(event("identity.verification_session.processing", "vs_1"));
        webhookService.handleEventAtomically(event("identity.verification_session.canceled", "vs_1"));

        verify(ageVerificationService).applyOutcome("vs_1", AgeVerificationStatus.REQUIRES_INPUT, null);
        verify(ageVerificationService).applyOutcome("vs_1", AgeVerificationStatus.PROCESSING, null);
        verify(ageVerificationService).applyOutcome("vs_1", AgeVerificationStatus.CANCELED, null);
        org.mockito.Mockito.verifyNoInteractions(stripeClient);
    }

    @Test
    void createdAndRedactedEvents_areIgnored() {
        webhookService.handleEventAtomically(event("identity.verification_session.created", "vs_1"));
        webhookService.handleEventAtomically(event("identity.verification_session.redacted", "vs_1"));

        verify(ageVerificationService, never()).applyOutcome(any(), any(), any());
    }

    @Test
    void aDuplicateEventId_isNotProcessedTwice() {
        when(webhookEventRepository.insertIfAbsent(any(), eq("identity.verification_session.processing")))
            .thenReturn(0);

        webhookService.handleEventAtomically(event("identity.verification_session.processing", "vs_1"));

        verify(ageVerificationService, never()).applyOutcome(any(), any(), any());
    }
}
