package com.softropic.skillars.platform.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.softropic.skillars.platform.outbox.contract.OutboxMessageHandler;
import com.softropic.skillars.platform.payment.service.StripeCustomerErasureOutboxSupport.StripeCustomerErasurePayload;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;

/**
 * Runs the Stripe half of a GDPR erasure enqueued by {@link StripeCustomerErasureOutboxSupport}: detach every
 * saved payment method, then delete the Stripe Customer. If Stripe refuses the delete (a 4xx other than
 * "already gone") the customer's name/email/phone/description and {@code userId} metadata are blanked instead,
 * so an open balance or dispute never leaves personal data behind. The charges, refunds and disputes stay at
 * Stripe either way, which is what financial-record retention needs.
 *
 * <p>Idempotent: a missing or already-deleted customer, or an already-detached card, is a no-op. A transient
 * Stripe failure throws, leaving the row for the next drain.
 */
@Slf4j
@Component
public class StripeCustomerErasureOutboxHandler implements OutboxMessageHandler {

    private static final String RESOURCE_MISSING = "resource_missing";

    private final StripeClient stripeClient;
    private final ObjectMapper objectMapper;

    public StripeCustomerErasureOutboxHandler(StripeClient stripeClient, ObjectMapper objectMapper) {
        this.stripeClient = stripeClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String aggregateType() {
        return StripeCustomerErasureOutboxSupport.AGGREGATE_TYPE;
    }

    @Override
    public void handle(String payload) {
        final StripeCustomerErasurePayload p;
        try {
            p = objectMapper.readValue(payload, StripeCustomerErasurePayload.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        String customerId = p.stripeCustomerId();
        try {
            Customer customer = stripeClient.retrieveCustomer(customerId);
            if (customer == null || Boolean.TRUE.equals(customer.getDeleted())) {
                return;
            }
            for (PaymentMethod pm : stripeClient.listCustomerPaymentMethods(customerId)) {
                detach(pm.getId());
            }
            try {
                stripeClient.deleteCustomer(customerId);
                log.info("[STRIPE_CUSTOMER_ERASED stripeCustomerId={}]", customerId);
            } catch (InvalidRequestException e) {
                if (RESOURCE_MISSING.equals(e.getCode())) {
                    return;
                }
                log.warn("[STRIPE_CUSTOMER_DELETE_REFUSED stripeCustomerId={} code={}] scrubbing instead",
                    customerId, e.getCode());
                stripeClient.scrubCustomer(customerId);
                log.info("[STRIPE_CUSTOMER_SCRUBBED stripeCustomerId={}]", customerId);
            }
        } catch (InvalidRequestException e) {
            if (RESOURCE_MISSING.equals(e.getCode())) {
                return;
            }
            throw new IllegalStateException("[STRIPE_CUSTOMER_ERASURE_FAILED] stripeCustomerId=" + customerId, e);
        } catch (StripeException e) {
            throw new IllegalStateException("[STRIPE_CUSTOMER_ERASURE_FAILED] stripeCustomerId=" + customerId, e);
        }
    }

    private void detach(String paymentMethodId) throws StripeException {
        try {
            stripeClient.detachPaymentMethod(paymentMethodId);
        } catch (InvalidRequestException e) {
            // Already detached (a repeat run) or gone: nothing left to remove.
            if (!RESOURCE_MISSING.equals(e.getCode()) && !isAlreadyDetached(e)) {
                throw e;
            }
        }
    }

    private static boolean isAlreadyDetached(InvalidRequestException e) {
        return e.getMessage() != null && e.getMessage().contains("not attached");
    }
}
