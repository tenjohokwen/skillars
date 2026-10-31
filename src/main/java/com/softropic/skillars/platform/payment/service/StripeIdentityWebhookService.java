package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.security.contract.AgeVerificationStatus;
import com.softropic.skillars.platform.security.service.AgeVerificationService;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.identity.VerificationSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * S-27: translates {@code identity.verification_session.*} events (delivered on the platform webhook endpoint, inside
 * {@link StripeWebhookService#handleEventAtomically}'s transaction and behind its event-id idempotency record) into
 * {@link AgeVerificationService#applyOutcome}.
 *
 * <p>The event payload never carries the verified date of birth; for {@code verified} it is read by retrieving the
 * session with {@code verified_outputs} expanded. A retrieval failure throws, so the webhook answers 5xx and Stripe
 * redelivers (the idempotency record rolls back with the transaction).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeIdentityWebhookService {

    static final String EVENT_PREFIX = "identity.verification_session.";

    private final StripeClient stripeClient;
    private final AgeVerificationService ageVerificationService;

    public void handle(Event event) {
        AgeVerificationStatus status = switch (event.getType()) {
            case EVENT_PREFIX + "verified" -> AgeVerificationStatus.VERIFIED;
            case EVENT_PREFIX + "requires_input" -> AgeVerificationStatus.REQUIRES_INPUT;
            case EVENT_PREFIX + "processing" -> AgeVerificationStatus.PROCESSING;
            case EVENT_PREFIX + "canceled" -> AgeVerificationStatus.CANCELED;
            default -> null;
        };
        if (status == null) {
            log.debug("[STRIPE_IDENTITY_WEBHOOK_IGNORED_TYPE type={}]", event.getType());
            return;
        }
        String sessionId = sessionId(event);
        LocalDate verifiedDob = status == AgeVerificationStatus.VERIFIED ? readVerifiedDob(sessionId) : null;
        ageVerificationService.applyOutcome(sessionId, status, verifiedDob);
    }

    private LocalDate readVerifiedDob(String sessionId) {
        try {
            VerificationSession session = stripeClient.retrieveVerificationSessionWithOutputs(sessionId);
            VerificationSession.VerifiedOutputs.Dob dob =
                session.getVerifiedOutputs() == null ? null : session.getVerifiedOutputs().getDob();
            if (dob == null || dob.getYear() == null || dob.getMonth() == null || dob.getDay() == null) {
                return null;
            }
            return LocalDate.of(dob.getYear().intValue(), dob.getMonth().intValue(), dob.getDay().intValue());
        } catch (StripeException | DateTimeException e) {
            log.error("[STRIPE_IDENTITY_VERIFIED_OUTPUTS_FAILED sessionId={} errorMessage={}]", sessionId, e.getMessage(), e);
            throw new IllegalStateException("Could not read verified outputs for " + sessionId, e);
        }
    }

    private String sessionId(Event event) {
        try {
            Object obj = event.getDataObjectDeserializer().deserializeUnsafe();
            if (!(obj instanceof VerificationSession session)) {
                throw new IllegalStateException("expected VerificationSession for " + event.getType());
            }
            return session.getId();
        } catch (EventDataObjectDeserializationException e) {
            log.error("[STRIPE_WEBHOOK_DESERIALIZE_FAILED type={} error={}]", event.getType(), e.getMessage(), e);
            throw new RuntimeException("Webhook deserialization failed for type " + event.getType(), e);
        }
    }
}
