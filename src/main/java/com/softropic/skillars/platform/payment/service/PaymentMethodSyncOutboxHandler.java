package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.service.PaymentMethodSyncOutboxSupport.PaymentMethodSyncPayload;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * fix/payment-subscription-resume-and-async-sync: runs a payment-method sync enqueued by
 * {@link PaymentMethodSyncOutboxSupport}. Idempotent — see
 * {@link SubscriptionService#syncPaymentMethodToSubscriptions}: setting the default payment method is
 * convergent, an invoice is only paid while still {@code open}, and a subscription is only resumed while
 * Stripe still reports it {@code paused}. A transient Stripe failure throws, leaving the row for the next
 * drain; a decline or a permanent 4xx is logged and the row completes.
 *
 * <p>skillars-deferred-176 (review round 3): runs under {@link StripeCustomerRepository#acquireCustomerOutboxLock}, shared
 * with the email-sync and erasure handlers, and reads the user only after it. A card reapply that committed just before
 * an erasure queues this sync legitimately; once that erasure has committed the sync is skipped, so Stripe never gets a
 * default card, an invoice paid or a subscription resumed for an erased payer.
 */
@Slf4j
@Component
public class PaymentMethodSyncOutboxHandler implements OutboxMessageHandler {

    private final SubscriptionService subscriptionService;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * {@code @Lazy}: SubscriptionService enqueues through {@link PaymentMethodSyncOutboxSupport} ->
     * OutboxService -> OutboxRowProcessor, which collects every handler — this one included — so an eager
     * reference back to SubscriptionService would be a constructor cycle.
     */
    public PaymentMethodSyncOutboxHandler(@Lazy SubscriptionService subscriptionService,
                                          StripeCustomerRepository stripeCustomerRepository, UserRepository userRepository,
                                          ObjectMapper objectMapper) {
        this.subscriptionService = subscriptionService;
        this.stripeCustomerRepository = stripeCustomerRepository;
        this.userRepository = userRepository;
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
        // Review round 5: the id only, so the superseded-card check in syncPaymentMethodToSubscriptions reads the row after
        // the lock wait rather than an entity cached before it (an older card's sync would otherwise win over a newer one).
        stripeCustomerRepository.findStripeCustomerIdByParentId(p.userId())
            .filter(id -> !id.isBlank())
            .ifPresent(stripeCustomerRepository::acquireCustomerOutboxLock);
        Optional<User> user = userRepository.findOneById(p.userId());
        if (user.isEmpty() || StripeCustomerCardWrites.isErasureTombstone(p.userId(), user.get().getEmail())) {
            log.info("[PAYMENT_METHOD_SYNC_SKIPPED userId={}] account gone or erased", p.userId());
            return;
        }
        subscriptionService.syncPaymentMethodToSubscriptions(p.userId(), p.paymentMethodId());
    }
}
