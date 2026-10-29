package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * fix/payment-subscription-resume-and-async-sync: moving a user's Stripe subscriptions onto a newly saved
 * card (default payment method, open-invoice retry, resume of a paused subscription) goes onto the durable
 * outbox instead of running inside the save-card request, so a Stripe outage neither fails nor slows the
 * save and a failed sync is retried by the outbox drain/sweep.
 *
 * <p>{@code MANDATORY}: called from {@link SubscriptionService#savePaymentMethodAndScheduleSync} so the
 * outbox row commits atomically with the saved card. {@link PaymentMethodSyncOutboxHandler} runs it after
 * commit; the handler is idempotent and skips a row whose card has since been replaced.
 */
@Component
@RequiredArgsConstructor
public class PaymentMethodSyncOutboxSupport {

    public static final String AGGREGATE_TYPE = "SUBSCRIPTION_PAYMENT_METHOD_SYNC";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueSync(Long userId, String paymentMethodId) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(new PaymentMethodSyncPayload(userId, paymentMethodId));
        } catch (JsonProcessingException e) {
            // Two scalar fields; cannot realistically fail. Throw so the save rolls back rather than
            // committing a card whose subscriptions are never moved onto it.
            throw new IllegalStateException("[PAYMENT_METHOD_SYNC_ENQUEUE_FAILED] userId=" + userId, e);
        }
        outboxService.enqueue(AGGREGATE_TYPE, json);
        outboxService.requestDrainAfterCommit();
    }

    /** Outbox payload: the user whose subscriptions move, and the card they saved. */
    public record PaymentMethodSyncPayload(Long userId, String paymentMethodId) {
    }
}
