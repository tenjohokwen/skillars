package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.security.contract.event.AgeVerificationConcludedEvent;
import com.stripe.exception.StripeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * S-27: tells Stripe about a concluded session only after the outcome committed. A verified/underage session is
 * redacted (Stripe deletes the document images and verified data — we keep only the outcome row); a failed one is
 * cancelled so retrying needs a new, counted, session. Best-effort: the outcome is already durable and a failure here
 * must not turn into a webhook retry, so it is logged for the operator.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeIdentityConclusionListener {

    private final StripeClient stripeClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(AgeVerificationConcludedEvent event) {
        try {
            switch (event.action()) {
                case REDACT -> stripeClient.redactVerificationSession(event.sessionId());
                case CANCEL -> stripeClient.cancelVerificationSession(event.sessionId());
            }
        } catch (StripeException | RuntimeException e) {
            log.error("[AGE_VERIFICATION_STRIPE_{}_FAILED sessionId={} errorMessage={}]",
                event.action(), event.sessionId(), e.getMessage(), e);
        }
    }
}
