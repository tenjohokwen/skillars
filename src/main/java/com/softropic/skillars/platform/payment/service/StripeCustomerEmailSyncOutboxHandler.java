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
import com.stripe.exception.StripeException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Runs a {@code STRIPE_CUSTOMER_EMAIL_SYNC} enqueued by {@link StripeCustomerEmailSyncOutboxSupport}: sets the Stripe customer's
 * email to the user's CURRENT email, so a confirm followed by an undo ends on the restored address whatever order the two rows
 * drain in. A no-op for a user or customer row that is gone, for a locked account (an undo does not refuse one) and for an erased
 * account (its address is the {@code @erased.io} tombstone, and the erasure scrubs the Stripe customer itself), and for a customer
 * Stripe no longer has. Any other Stripe failure throws, leaving the row for the next drain.
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
        Optional<User> user = userRepository.findOneById(userId);
        if (user.isEmpty() || user.get().isLocked() || user.get().getEmail() == null || user.get().getEmail().isBlank()
                || user.get().getEmail().endsWith(ERASED_EMAIL_DOMAIN)) {
            log.info("[STRIPE_CUSTOMER_EMAIL_SYNC_SKIPPED userId={}] account gone, locked or erased", userId);
            return;
        }
        Optional<String> customerId = stripeCustomerRepository.findById(userId).map(StripeCustomer::getStripeCustomerId);
        if (customerId.isEmpty() || customerId.get().isBlank()) {
            return;
        }
        try {
            stripeClient.updateCustomerEmail(customerId.get(), user.get().getEmail());
            log.info("[STRIPE_CUSTOMER_EMAIL_SYNCED userId={} stripeCustomerId={}]", userId, customerId.get());
        } catch (InvalidRequestException e) {
            if (RESOURCE_MISSING.equals(e.getCode())) {
                log.info("[STRIPE_CUSTOMER_EMAIL_SYNC_SKIPPED userId={}] Stripe no longer has the customer", userId);
                return;
            }
            throw new IllegalStateException("[STRIPE_CUSTOMER_EMAIL_SYNC_FAILED] userId=" + userId, e);
        } catch (StripeException e) {
            throw new IllegalStateException("[STRIPE_CUSTOMER_EMAIL_SYNC_FAILED] userId=" + userId, e);
        }
    }
}
