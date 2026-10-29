package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.service.PaymentMethodSyncOutboxSupport.PaymentMethodSyncPayload;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

/**
 * fix/payment-subscription-resume-and-async-sync: runs a payment-method sync enqueued by
 * {@link PaymentMethodSyncOutboxSupport}. Idempotent — see
 * {@link SubscriptionService#syncPaymentMethodToSubscriptions}: setting the default payment method is
 * convergent, an invoice is only paid while still {@code open}, and a subscription is only resumed while
 * Stripe still reports it {@code paused}. A transient Stripe failure throws, leaving the row for the next
 * drain; a decline or a permanent 4xx is logged and the row completes.
 */
@Component
public class PaymentMethodSyncOutboxHandler implements OutboxMessageHandler {

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    /**
     * {@code @Lazy}: SubscriptionService enqueues through {@link PaymentMethodSyncOutboxSupport} ->
     * OutboxService -> OutboxRowProcessor, which collects every handler — this one included — so an eager
     * reference back to SubscriptionService would be a constructor cycle.
     */
    public PaymentMethodSyncOutboxHandler(@Lazy SubscriptionService subscriptionService, ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String aggregateType() {
        return PaymentMethodSyncOutboxSupport.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final PaymentMethodSyncPayload p;
        try {
            p = objectMapper.readValue(payload, PaymentMethodSyncPayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        subscriptionService.syncPaymentMethodToSubscriptions(p.userId(), p.paymentMethodId());
    }
}
