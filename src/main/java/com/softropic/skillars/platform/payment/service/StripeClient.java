package com.softropic.skillars.platform.payment.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.model.Subscription;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Thin wrapper around the Stripe SDK static calls so they can be mocked in unit tests.
 */
@Component
public class StripeClient {

    public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params) throws StripeException {
        return PaymentIntent.create(params);
    }

    public PaymentIntent retrievePaymentIntent(String id) throws StripeException {
        return PaymentIntent.retrieve(id);
    }

    public Refund createRefund(RefundCreateParams params) throws StripeException {
        return Refund.create(params);
    }

    public SetupIntent createSetupIntent(SetupIntentCreateParams params) throws StripeException {
        return SetupIntent.create(params);
    }

    public Customer createCustomer(CustomerCreateParams params) throws StripeException {
        return Customer.create(params);
    }

    public void attachPaymentMethod(String stripeCustomerId, String paymentMethodId) throws StripeException {
        PaymentMethod pm = PaymentMethod.retrieve(paymentMethodId);
        com.stripe.param.PaymentMethodAttachParams attachParams =
            com.stripe.param.PaymentMethodAttachParams.builder()
                .setCustomer(stripeCustomerId)
                .build();
        pm.attach(attachParams);
    }

    /**
     * Creates a Stripe Subscription. Returns the full Subscription so callers can extract
     * subscriptionId and currentPeriodEnd in one call.
     * Idempotency key is derived from customerId+priceId to prevent duplicate subscriptions on retry.
     */
    public Subscription createSubscription(String stripeCustomerId, String priceId, String paymentMethodId)
            throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey("sub-create-" + stripeCustomerId + "-" + priceId)
            .build();
        SubscriptionCreateParams params = SubscriptionCreateParams.builder()
            .setCustomer(stripeCustomerId)
            .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
            .setDefaultPaymentMethod(paymentMethodId)
            .build();
        return Subscription.create(params, options);
    }

    /**
     * Updates the subscription to a new price (upgrade/downgrade with proration).
     * Returns the new currentPeriodEnd as Instant.
     */
    public Instant updateSubscriptionTier(String stripeSubscriptionId, String newPriceId) throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey("sub-update-" + stripeSubscriptionId + "-" + newPriceId)
            .build();
        Subscription existing = Subscription.retrieve(stripeSubscriptionId);
        String itemId = existing.getItems().getData().get(0).getId();
        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
            .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
            .addItem(SubscriptionUpdateParams.Item.builder()
                .setId(itemId)
                .setPrice(newPriceId)
                .build())
            .build();
        Subscription updated = existing.update(params, options);
        return Instant.ofEpochSecond(updated.getCurrentPeriodEnd());
    }

    /**
     * Cancels the subscription at period end. Returns the new currentPeriodEnd.
     */
    public Instant cancelSubscriptionAtPeriodEnd(String stripeSubscriptionId) throws StripeException {
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey("sub-cancel-" + stripeSubscriptionId)
            .build();
        Subscription sub = Subscription.retrieve(stripeSubscriptionId);
        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
            .setCancelAtPeriodEnd(true)
            .build();
        Subscription updated = sub.update(params, options);
        return Instant.ofEpochSecond(updated.getCurrentPeriodEnd());
    }
}
