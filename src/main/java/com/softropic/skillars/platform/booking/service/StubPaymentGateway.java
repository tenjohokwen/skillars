package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.PaymentGateway;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class StubPaymentGateway implements PaymentGateway {

    // TODO(7.1): Remove and replace with StripePaymentGateway from platform.payment
    @Override
    public String capturePayment(BigDecimal amount, String currency) {
        return UUID.randomUUID().toString();
    }
}
