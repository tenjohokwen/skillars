package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.contract.event.AccountEmailChangedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * skillars-deferred-175 AC4 (RS03c): Stripe gets the email only when the customer is created, so an email change would leave
 * receipts going to the old address. On {@link AccountEmailChangedEvent} this puts a {@code STRIPE_CUSTOMER_EMAIL_SYNC} on the
 * durable outbox, in the email-change transaction ({@code MANDATORY}): it commits or rolls back with the change, and
 * {@link StripeCustomerEmailSyncOutboxHandler} calls Stripe after commit. Only for a user with a Stripe customer row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeCustomerEmailSyncOutboxSupport {

    public static final String AGGREGATE_TYPE = "STRIPE_CUSTOMER_EMAIL_SYNC";

    private final StripeCustomerRepository stripeCustomerRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountEmailChanged(AccountEmailChangedEvent event) {
        Long userId = event.userId();
        if (!stripeCustomerRepository.existsById(userId)) {
            return;
        }
        final String json;
        try {
            json = objectMapper.writeValueAsString(new StripeCustomerEmailSyncPayload(userId));
        } catch (JsonProcessingException e) {
            // One scalar field; cannot realistically fail. Throwing rolls the email change back rather than losing the sync.
            throw new IllegalStateException("[STRIPE_CUSTOMER_EMAIL_SYNC_ENQUEUE_FAILED] userId=" + userId, e);
        }
        outboxService.enqueue(AGGREGATE_TYPE, json);
        outboxService.requestDrainAfterCommit();
        log.info("[STRIPE_CUSTOMER_EMAIL_SYNC_SCHEDULED userId={}]", userId);
    }

    /** Outbox payload: the user id only; the handler reads the address current when it runs. */
    public record StripeCustomerEmailSyncPayload(Long userId) {
    }
}
