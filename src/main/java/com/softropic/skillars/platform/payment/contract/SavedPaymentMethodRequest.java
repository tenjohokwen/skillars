package com.softropic.skillars.platform.payment.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SavedPaymentMethodRequest(
    @NotBlank @Pattern(regexp = StripeIdFormat.PAYMENT_METHOD_ID_REGEX, message = "paymentMethodId is not a Stripe payment method id")
    String paymentMethodId
) {}
