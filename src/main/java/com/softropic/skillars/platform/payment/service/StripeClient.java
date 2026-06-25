package com.softropic.skillars.platform.payment.service;

import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import org.springframework.stereotype.Component;

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
}
