package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPR Art. 17 for Stripe (security finding S-19): the Stripe Customer (name/email/metadata) and its saved
 * payment methods live at Stripe, so the erasure transaction cannot remove them itself. It calls
 * {@link #scheduleErasure} instead, which — atomically with the erasure — drops the locally stored card id and
 * puts the Stripe-side cleanup on the durable outbox. {@link StripeCustomerErasureOutboxHandler} runs it after
 * commit, off the erasure path, so a Stripe outage neither rolls back nor fails the erasure and is retried.
 *
 * <p>The {@code payment.stripe_customers} row itself is kept: ledger, refund and payout rows reference the
 * Stripe customer/PaymentIntent ids for financial-record retention, and those ids carry no personal data
 * once the customer is deleted or scrubbed at Stripe. Only the saved-card id is nulled here.
 *
 * <p>{@code MANDATORY}: must join the erasure transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeCustomerErasureOutboxSupport {

    public static final String AGGREGATE_TYPE = "STRIPE_CUSTOMER_ERASURE";

    private final StripeCustomerRepository stripeCustomerRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleErasure(Long userId) {
        stripeCustomerRepository.findById(userId).ifPresent(customer -> {
            customer.setStripePaymentMethodId(null);
            stripeCustomerRepository.save(customer);
            final String json;
            try {
                json = objectMapper.writeValueAsString(new StripeCustomerErasurePayload(customer.getStripeCustomerId()));
            } catch (JsonProcessingException e) {
                // One scalar field; cannot realistically fail. Throw so the erasure rolls back and is
                // re-driven rather than committing COMPLETED with the Stripe customer never scheduled.
                throw new IllegalStateException("[STRIPE_CUSTOMER_ERASURE_ENQUEUE_FAILED] userId=" + userId, e);
            }
            outboxService.enqueue(AGGREGATE_TYPE, json);
            outboxService.requestDrainAfterCommit();
            log.info("[STRIPE_CUSTOMER_ERASURE_SCHEDULED userId={}]", userId);
        });
    }

    /** Outbox payload: the Stripe customer id only (no personal data). */
    public record StripeCustomerErasurePayload(String stripeCustomerId) {
    }
}
