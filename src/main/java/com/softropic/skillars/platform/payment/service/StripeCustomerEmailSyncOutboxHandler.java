package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.repo.StripeCustomer;
import com.softropic.skillars.platform.payment.repo.StripeCustomerRepository;
import com.softropic.skillars.platform.payment.service.StripeCustomerEmailSyncOutboxSupport.StripeCustomerEmailSyncPayload;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Runs a {@code STRIPE_CUSTOMER_EMAIL_SYNC} enqueued by {@link StripeCustomerEmailSyncOutboxSupport}: sets the Stripe customer's
 * email to the user's CURRENT email, so a confirm followed by an undo ends on the restored address whatever order the two rows
 * drain in. A no-op for a user or customer row that is gone, for an erased account (its address is the {@code @erased.io}
 * tombstone, set in the same transaction as the erasure's lock, and the erasure scrubs the Stripe customer itself), and for a
 * customer Stripe no longer has. A locked account is synced: a suspension is not an erasure, and an undo that restores the
 * owner's address must reach Stripe even while the account is suspended. Any other request Stripe rejects is logged and dropped,
 * since retrying it cannot succeed; a rate limit or outage throws, leaving the row for the next drain.
 *
 * <p>skillars-deferred-176 AC1: runs under {@link StripeCustomerRepository#acquireCustomerOutboxLock}, shared with
 * {@link StripeCustomerErasureOutboxHandler}, so a confirm's sync and an undo's sync, or a sync and an erasure, never
 * reach Stripe out of order.
 */
@Slf4j
@Component
public class StripeCustomerEmailSyncOutboxHandler implements OutboxMessageHandler {

    private static final String RESOURCE_MISSING = "resource_missing";
    private static final String ERASED_EMAIL_DOMAIN = "@erased.io";

    private final StripeClient stripeClient;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public StripeCustomerEmailSyncOutboxHandler(StripeClient stripeClient, StripeCustomerRepository stripeCustomerRepository,
                                                UserRepository userRepository, ObjectMapper objectMapper) {
        this.stripeClient = stripeClient;
        this.stripeCustomerRepository = stripeCustomerRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String aggregateType() {
        return StripeCustomerEmailSyncOutboxSupport.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final Long userId;
        try {
            userId = objectMapper.readValue(payload, StripeCustomerEmailSyncPayload.class).userId();
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        Optional<String> customerId = stripeCustomerRepository.findById(userId).map(StripeCustomer::getStripeCustomerId);
        if (customerId.isEmpty() || customerId.get().isBlank()) {
            return;
        }
        // skillars-deferred-176 AC1: the user is read only after the lock, so a confirm/undo or erasure that committed
        // while another handler held it is what this one sees.
        stripeCustomerRepository.acquireCustomerOutboxLock(customerId.get());
        Optional<User> user = userRepository.findOneById(userId);
        if (user.isEmpty() || user.get().getEmail() == null || user.get().getEmail().isBlank()
                || user.get().getEmail().endsWith(ERASED_EMAIL_DOMAIN)) {
            log.info("[STRIPE_CUSTOMER_EMAIL_SYNC_SKIPPED userId={}] account gone or erased", userId);
            return;
        }
        try {
            stripeClient.updateCustomerEmail(customerId.get(), user.get().getEmail());
            log.info("[STRIPE_CUSTOMER_EMAIL_SYNCED userId={} stripeCustomerId={}]", userId, customerId.get());
        } catch (RateLimitException e) {
            // A subclass of InvalidRequestException in this SDK, but transient.
            throw new IllegalStateException("[STRIPE_CUSTOMER_EMAIL_SYNC_FAILED] userId=" + userId, e);
        } catch (InvalidRequestException e) {
            if (RESOURCE_MISSING.equals(e.getCode())) {
                log.info("[STRIPE_CUSTOMER_EMAIL_SYNC_SKIPPED userId={}] Stripe no longer has the customer", userId);
                return;
            }
            log.error("[STRIPE_CUSTOMER_EMAIL_SYNC_REJECTED userId={} stripeCustomerId={} code={} status={}] not retried",
                userId, customerId.get(), e.getCode(), e.getStatusCode(), e);
        } catch (StripeException e) {
            throw new IllegalStateException("[STRIPE_CUSTOMER_EMAIL_SYNC_FAILED] userId=" + userId, e);
        }
    }
}
