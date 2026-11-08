package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.service.OutboxService;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.security.contract.event.AccountEmailChangedEvent;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * skillars-deferred-175 AC4 (RS03c): Stripe gets the email only when the customer is created, so an email change would leave
 * receipts going to the old address. On {@link AccountEmailChangedEvent} this puts a {@code STRIPE_CUSTOMER_EMAIL_SYNC} on the
 * durable outbox, in the email-change transaction ({@code MANDATORY}): it commits or rolls back with the change, and
 * {@link StripeCustomerEmailSyncOutboxHandler} calls Stripe after commit. Only for a user with a Stripe customer row.
 *
 * <p>skillars-deferred-176 AC2 (RS03e): a change that commits while the user's first Stripe customer is being created finds no
 * row yet and enqueues nothing, and the customer was created with the old address. The two creation paths therefore read
 * {@link #currentEmail} before creating the customer and call {@link #syncIfEmailChangedSince} after inserting the row.
 *
 * <p>Review round 4: an erasure that commits in the same window also finds no row, so it schedules no Stripe-side erasure
 * and the customer keeps the real email. When the address read after the insert is the user's erasure tombstone, that
 * erasure is scheduled here instead ({@link StripeCustomerErasureOutboxSupport#scheduleErasure}, which also drops the card
 * the insert saved).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeCustomerEmailSyncOutboxSupport {

    public static final String AGGREGATE_TYPE = "STRIPE_CUSTOMER_EMAIL_SYNC";

    private final StripeCustomerRepository stripeCustomerRepository;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final StripeCustomerErasureOutboxSupport erasureOutboxSupport;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccountEmailChanged(AccountEmailChangedEvent event) {
        Long userId = event.userId();
        if (!stripeCustomerRepository.existsById(userId)) {
            return;
        }
        enqueue(userId);
        log.info("[STRIPE_CUSTOMER_EMAIL_SYNC_SCHEDULED userId={}]", userId);
    }

    /** The user's email now, or null; read before a Stripe customer is created, for {@link #syncIfEmailChangedSince}. */
    public String currentEmail(Long userId) {
        return userRepository.findOneById(userId).map(User::getEmail).orElse(null);
    }

    /**
     * Called after a new {@code stripe_customers} row is inserted: if the user has been erased meanwhile, schedules the
     * Stripe-side erasure; otherwise, if the user's email is no longer the one read before the customer was created,
     * enqueues a sync. Never throws: the customer exists at Stripe either way, and failing the caller would strand it.
     */
    public void syncIfEmailChangedSince(Long userId, String emailBefore) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                String emailNow = currentEmail(userId);
                if (StripeCustomerCardWrites.isErasureTombstone(userId, emailNow)) {
                    erasureOutboxSupport.scheduleErasure(userId);
                    log.warn("[STRIPE_CUSTOMER_ERASURE_SCHEDULED_LATE userId={}] erased while its Stripe customer was created", userId);
                    return;
                }
                if (Objects.equals(emailNow, emailBefore)) {
                    return;
                }
                enqueue(userId);
                log.info("[STRIPE_CUSTOMER_EMAIL_SYNC_SCHEDULED userId={}] email changed while the customer was created", userId);
            });
        } catch (RuntimeException e) {
            log.error("[STRIPE_CUSTOMER_EMAIL_SYNC_ENQUEUE_FAILED] userId={} after customer creation", userId, e);
        }
    }

    private void enqueue(Long userId) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(new StripeCustomerEmailSyncPayload(userId));
        } catch (JsonProcessingException e) {
            // One scalar field; cannot realistically fail. Throwing rolls the email change back rather than losing the sync.
            throw new IllegalStateException("[STRIPE_CUSTOMER_EMAIL_SYNC_ENQUEUE_FAILED] userId=" + userId, e);
        }
        outboxService.enqueue(AGGREGATE_TYPE, json);
        outboxService.requestDrainAfterCommit();
    }

    /** Outbox payload: the user id only; the handler reads the address current when it runs. */
    public record StripeCustomerEmailSyncPayload(Long userId) {
    }
}
